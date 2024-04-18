package no.nav.security.mock.oauth2.token

import com.nimbusds.jose.jwk.KeyType
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.id.Issuer
import io.kotest.assertions.asClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.security.mock.oauth2.extensions.verifySignatureAndIssuer
import no.nav.security.mock.oauth2.testutils.nimbusTokenRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

internal class OAuth2TokenProviderRSATest {
    private val tokenProvider = OAuth2TokenProvider()

    @Test
    fun `public jwks returns public part of JWKs`() {
        val jwkSet = tokenProvider.publicJwkSet()
        jwkSet.keys.any { it.isPrivate } shouldNotBe true
    }

    @Test
    fun `all keys in public jwks should contain kty, use and kid`() {
        val jwkSet = tokenProvider.publicJwkSet()
        jwkSet.keys.forEach {
            it.keyID shouldNotBe null
            it.keyType shouldBe KeyType.RSA
            it.keyUse shouldBe KeyUse.SIGNATURE
        }
    }

    @Test
    fun `claims from tokencallback should be added to token in tokenExchange`() {
        val initialToken =
            tokenProvider.jwt(
                mapOf(
                    "iss" to "http://initialissuer",
                    "sub" to "initialsubject",
                    "aud" to "initialaudience",
                    "initialclaim" to "initialclaim",
                ),
            )

        tokenProvider.exchangeAccessToken(
            tokenRequest =
                nimbusTokenRequest(
                    "myclient",
                    "grant_type" to GrantType.JWT_BEARER.value,
                    "scope" to "scope1",
                    "assertion" to initialToken.serialize(),
                ),
            issuerUrl = "http://default_if_not_overridden".toHttpUrl(),
            claimsSet = initialToken.jwtClaimsSet,
            oAuth2TokenCallback =
                DefaultOAuth2TokenCallback(
                    claims =
                        mapOf(
                            "extraclaim" to "extra",
                            "iss" to "http://overrideissuer",
                        ),
                ),
        ).jwtClaimsSet.asClue {
            it.issuer shouldBe "http://overrideissuer"
            it.subject shouldBe "initialsubject"
            it.audience shouldBe listOf("scope1")
            it.claims["initialclaim"] shouldBe "initialclaim"
            it.claims["extraclaim"] shouldBe "extra"
        }
    }

    @Test
    fun `publicJwks should return different signing key for each issuerId`() {
        val keys1 = tokenProvider.publicJwkSet("issuer1").toJSONObject()
        keys1 shouldBe tokenProvider.publicJwkSet("issuer1").toJSONObject()
        val keys2 = tokenProvider.publicJwkSet("issuer2").toJSONObject()
        keys2 shouldNotBe keys1
    }

    @ParameterizedTest
    @ValueSource(strings = ["issuer1", "issuer2"])
    fun `ensure idToken is signed with same key as returned from public jwks`(issuerId: String) {
        val issuer = Issuer("http://localhost/$issuerId")
        idToken(issuer.toString()).verifySignatureAndIssuer(issuer, tokenProvider.publicJwkSet(issuerId))

        shouldThrow<BadJOSEException> {
            idToken(issuer.toString()).verifySignatureAndIssuer(issuer, tokenProvider.publicJwkSet("shouldfail"))
        }
    }

    @Test
    fun `token should have issuedAt set to systemTime if set, otherwise use now()`() {
        val yesterDay = Instant.now().minus(1, ChronoUnit.DAYS)
        val tokenProvider = OAuth2TokenProvider(systemTime = yesterDay)

        tokenProvider.exchangeAccessToken(
            tokenRequest =
                nimbusTokenRequest(
                    "id",
                    "grant_type" to GrantType.CLIENT_CREDENTIALS.value,
                    "scope" to "scope1",
                ),
            issuerUrl = "http://default_if_not_overridden".toHttpUrl(),
            claimsSet = tokenProvider.jwt(mapOf()).jwtClaimsSet,
            oAuth2TokenCallback = DefaultOAuth2TokenCallback(),
        ).asClue {
            it.jwtClaimsSet.issueTime shouldBe Date.from(tokenProvider.systemTime)
            println(it.serialize())
        }
    }

    private fun idToken(issuerUrl: String): SignedJWT =
        tokenProvider.idToken(
            tokenRequest =
                nimbusTokenRequest(
                    "client1",
                    "grant_type" to "authorization_code",
                    "code" to "123",
                ),
            issuerUrl = issuerUrl.toHttpUrl(),
            oAuth2TokenCallback = DefaultOAuth2TokenCallback(),
        )
}
