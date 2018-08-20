package com.gu.googleauth

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.time.Clock
import java.util.{Base64, Date}

import com.gu.googleauth.AntiForgeryChecker._
import com.gu.play.secretrotation.DualSecretTransition.InitialSecret
import com.gu.play.secretrotation.SnapshotProvider
import io.jsonwebtoken.SignatureAlgorithm.HS256
import io.jsonwebtoken._
import org.joda.time.Duration
import play.api.http.{HttpConfiguration, SecretConfiguration}
import play.api.libs.json.JsValue
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.mvc.Results.Redirect
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * The configuration class for Google authentication
  * @param clientId The ClientID from the developer dashboard
  * @param clientSecret The client secret from the developer dashboard
  * @param redirectUrl The URL to return to after authentication has completed
  * @param domain An optional domain to restrict login to (e.g. guardian.co.uk)
  * @param maxAuthAge An optional duration after which you want a user to be prompted for their password again
  * @param enforceValidity A boolean indicating whether you want a user to be re-authenticated when their session expires
  * @param prompt An optional space delimited, case sensitive list of ASCII string values that specifies whether the
  *               Authorization Server prompts the End-User for reauthentication and consent
 * @param antiForgeryChecker configuration for the checks that ensure the OAuth callback can't be forged
  */
case class GoogleAuthConfig private(
  clientId: String,
  clientSecret: String,
  redirectUrl: String,
  domain: Option[String],
  maxAuthAge: Option[Duration],
  enforceValidity: Boolean,
  prompt: Option[String],
  antiForgeryChecker: AntiForgeryChecker
)
object GoogleAuthConfig {
  private val defaultMaxAuthAge = None
  private val defaultEnforceValidity = true
  private val defaultPrompt = None

  def apply(
    clientId: String,
    clientSecret: String,
    redirectUrl: String,
    domain: String,
    maxAuthAge: Option[Duration] = defaultMaxAuthAge,
    enforceValidity: Boolean = defaultEnforceValidity,
    prompt: Option[String] = defaultPrompt,
    antiForgeryChecker: AntiForgeryChecker

  ): GoogleAuthConfig = GoogleAuthConfig(clientId, clientSecret, redirectUrl, Some(domain), maxAuthAge, enforceValidity, prompt, antiForgeryChecker)

  /**
    * Creates a GoogleAuthConfig that does not restrict acceptable email domains.
    * This means any Google account can be used to gain access. If you mean to restrict
    * access to certain email domains use the `apply` method instead.
    */
  def withNoDomainRestriction(
    clientId: String,
    clientSecret: String,
    redirectUrl: String,
    maxAuthAge: Option[Duration] = defaultMaxAuthAge,
    enforceValidity: Boolean = defaultEnforceValidity,
    prompt: Option[String] = defaultPrompt,
    antiForgeryChecker: AntiForgeryChecker
  ): GoogleAuthConfig =
    GoogleAuthConfig(clientId, clientSecret, redirectUrl, None, maxAuthAge, enforceValidity, prompt, antiForgeryChecker)
}

/**
  * When the OAuth callback returns to our app, we need to ensure that this is the end of a valid authentication
  * sequence that we initiated, and not a forged redirect. Rather than use a nonce, we use a signed session id
  * in a short-lifetime Json Web Token, allowing us to cope better with concurrent authentication requests from the
  * same browser session.
  *
  * "One good choice for a state token is a string of 30 or so characters constructed using a high-quality
  * random-number generator. Another is a hash generated by signing some of your session state variables with
  * a key that is kept secret on your back-end."
  * - https://developers.google.com/identity/protocols/OpenIDConnect#createxsrftoken
  *
  * The design here is partially based on a IETF draft for "Encoding claims in the OAuth 2 state parameter ...":
  * https://tools.ietf.org/html/draft-bradley-oauth-jwt-encoded-state-01
  *
  * @param secretsProvider see https://github.com/guardian/play-secret-rotation
  * @param signatureAlgorithm defaults to a sensible value, but you can consider using
  *                           [[AntiForgeryChecker#signatureAlgorithmFromPlay]]
  */
case class AntiForgeryChecker(
  secretsProvider: SnapshotProvider,
  signatureAlgorithm: SignatureAlgorithm = HS256, // same default currently used by Play: https://github.com/playframework/playframework/blob/a39b208/framework/src/play/src/main/scala/play/api/http/HttpConfiguration.scala#L336
  sessionIdKeyName: String = "play-googleauth-session-id"
) {

  private def base64EncodedSecretFrom(sc: SecretConfiguration): String =
    Base64.getEncoder.encodeToString(sc.secret.getBytes(UTF_8))

  def ensureUserHasSessionId(t: String => Future[Result])(implicit request: RequestHeader, ec: ExecutionContext):Future[Result] = {
    val sessionId = request.session.get(sessionIdKeyName).getOrElse(generateSessionId())

    t(sessionId).map(_.addingToSession(sessionIdKeyName -> sessionId))
  }

  def generateToken(sessionId: String)(implicit clock: Clock = Clock.systemUTC) : String = Jwts.builder()
    .setExpiration(Date.from(clock.instant().plusSeconds(60)))
    .claim(SessionIdJWTClaimPropertyName, sessionId)
    .signWith(signatureAlgorithm, base64EncodedSecretFrom(secretsProvider.snapshot().secrets.active))
    .compact()

  def checkChoiceOfSigningAlgorithm(claims: Jws[Claims]): Try[Unit] =
    if (claims.getHeader.getAlgorithm == signatureAlgorithm.getValue) Success(()) else
      Failure(throw new IllegalArgumentException(s"the anti forgery token is not signed with $signatureAlgorithm"))

  def checkTokenContainsCorrectSessionId(claims: Jws[Claims], userSessionId: String): Try[Unit] =
    if (claims.getBody.get(SessionIdJWTClaimPropertyName) == userSessionId) Success(()) else
      Failure(throw new IllegalArgumentException("the session ID found in the anti forgery token does not match the Play session ID"))

  def verifyToken(request: RequestHeader): Try[Unit] = for {
    sessionIdFromPlaySession <- Try(request.session.get(sessionIdKeyName).getOrElse(throw new IllegalArgumentException("No Play session ID found")))
    oauthAntiForgeryState <- Try(request.getQueryString("state").getOrElse(throw new IllegalArgumentException("No anti-forgery state returned in OAuth callback")))
    jwtClaims <- parseJwtClaimsFrom(oauthAntiForgeryState)
    _ <- checkChoiceOfSigningAlgorithm(jwtClaims)
    _ <- checkTokenContainsCorrectSessionId(jwtClaims, sessionIdFromPlaySession)
  } yield ()

  private def parseJwtClaimsFrom(oauthAntiForgeryState: String) = secretsProvider.snapshot().decode[Try[Jws[Claims]]]({
    sc => Try(Jwts.parser().setSigningKey(base64EncodedSecretFrom(sc)).parseClaimsJws(oauthAntiForgeryState))
  }, conclusiveDecode = {
    case Failure(_: SignatureException) => false // signature doesn't match this secret, try a different one
    case _ => true
  }).getOrElse(Failure(new SignatureException("OAuth anti-forgery state doesn't have a valid signature")))
}

object AntiForgeryChecker {
  private val random = new SecureRandom()
  def generateSessionId() = new BigInteger(130, random).toString(32)

  val SessionIdJWTClaimPropertyName = "rfp" // see https://tools.ietf.org/html/draft-bradley-oauth-jwt-encoded-state-01#section-2

  @deprecated("This method doesn't handle rotating secrets, use the standard `AntiForgeryChecker` constructor","0.7.7")
  def borrowSettingsFromPlay(httpConfiguration: HttpConfiguration): AntiForgeryChecker =
    AntiForgeryChecker(InitialSecret(httpConfiguration.secret), signatureAlgorithmFromPlay(httpConfiguration))

  /**
    * If you're happy using the Playframework, you're probably happy to use their choice of JWT
    * signature algorithm.
    */
  def signatureAlgorithmFromPlay(httpConfiguration: HttpConfiguration): SignatureAlgorithm =
    SignatureAlgorithm.forName(httpConfiguration.session.jwt.signatureAlgorithm)
}

class GoogleAuthException(val message: String, val throwable: Throwable = null) extends Exception(message, throwable)

object GoogleAuth {
  var discoveryDocumentHolder: Option[Future[DiscoveryDocument]] = None

  def discoveryDocument()(implicit context: ExecutionContext, ws: WSClient): Future[DiscoveryDocument] =
    if (discoveryDocumentHolder.isDefined) discoveryDocumentHolder.get
    else {
      val discoveryDocumentFuture = ws.url(DiscoveryDocument.url).get().map(r => DiscoveryDocument.fromJson(r.json))
      discoveryDocumentHolder = Some(discoveryDocumentFuture)
      discoveryDocumentFuture
    }

  def googleResponse[T](r: WSResponse)(block: JsValue => T): T = {
    r.status match {
      case errorCode if errorCode >= 400 =>
        // try to get error if google sent us an error doc
        val error = (r.json \ "error").asOpt[Error]
        error.map { e =>
          throw new GoogleAuthException(s"Error when calling Google: ${e.message}")
        }.getOrElse {
          throw new GoogleAuthException(s"Unknown error when calling Google [status=$errorCode, body=${r.body}]")
        }
      case normal => block(r.json)
    }
  }

  def redirectToGoogle(config: GoogleAuthConfig, sessionId: String)
                      (implicit request: RequestHeader, context: ExecutionContext, ws: WSClient): Future[Result] = {
    val userIdentity = UserIdentity.fromRequest(request)
    val queryString: Map[String, Seq[String]] = Map(
      "client_id" -> Seq(config.clientId),
      "response_type" -> Seq("code"),
      "scope" -> Seq("openid email profile"),
      "redirect_uri" -> Seq(config.redirectUrl),
      "state" -> Seq(config.antiForgeryChecker.generateToken(sessionId))) ++
      config.domain.map(domain => "hd" -> Seq(domain)) ++
      config.maxAuthAge.map(age => "max_auth_age" -> Seq(s"${age.getStandardSeconds}")) ++
      config.prompt.map(prompt => "prompt" -> Seq(prompt)) ++
      userIdentity.map(_.email).map("login_hint" -> Seq(_))

    discoveryDocument().map(dd => Redirect(s"${dd.authorization_endpoint}", queryString))
  }

  def validatedUserIdentity(config: GoogleAuthConfig)
        (implicit request: RequestHeader, context: ExecutionContext, ws: WSClient): Future[UserIdentity] = {

    Future.fromTry(config.antiForgeryChecker.verifyToken(request)).flatMap(_ => discoveryDocument()).flatMap { dd =>
      val code = request.queryString("code")
      ws.url(dd.token_endpoint).post {
        Map(
          "code" -> code,
          "client_id" -> Seq(config.clientId),
          "client_secret" -> Seq(config.clientSecret),
          "redirect_uri" -> Seq(config.redirectUrl),
          "grant_type" -> Seq("authorization_code")
        )
      }.flatMap { response =>
        googleResponse(response) { json =>
          val token = Token.fromJson(json)
          val jwt = token.jwt
          config.domain foreach { domain =>
            if (!jwt.claims.email.split("@").lastOption.contains(domain))
              throw new GoogleAuthException("Configured Google domain does not match")
          }
          ws.url(dd.userinfo_endpoint)
            .withHttpHeaders("Authorization" -> s"Bearer ${token.access_token}")
            .get().map { response =>
            googleResponse(response) { json =>
              val userInfo = UserInfo.fromJson(json)
              UserIdentity(
                jwt.claims.sub,
                jwt.claims.email,
                userInfo.given_name,
                userInfo.family_name,
                jwt.claims.exp,
                userInfo.picture
              )
            }
          }
        }
      }
    }
  }
}
