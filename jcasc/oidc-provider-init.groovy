import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import io.jenkins.plugins.oidc_provider.IdTokenCredentials
import io.jenkins.plugins.oidc_provider.IdTokenFileCredentials
import jenkins.model.Jenkins
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

def privateKeyFile = System.getenv('OIDC_PRIVATE_KEY_FILE')?.trim()
def privateKeyEnv = System.getenv('OIDC_PRIVATE_KEY')?.trim()
def issuerUrl = System.getenv('OIDC_ISSUER_URL')?.trim()

if (privateKeyFile && privateKeyEnv) {
    throw new IllegalStateException('Set only one of OIDC_PRIVATE_KEY_FILE or OIDC_PRIVATE_KEY.')
}
if (!privateKeyFile && !privateKeyEnv && !issuerUrl) {
    println '[OIDC-INIT] Key injection is disabled; leaving the configured credential unchanged.'
    return
}
if ((!privateKeyFile && !privateKeyEnv) || !issuerUrl) {
    throw new IllegalStateException('Set OIDC_ISSUER_URL and either OIDC_PRIVATE_KEY_FILE or OIDC_PRIVATE_KEY to inject the OIDC credential.')
}

def privateKeyText = privateKeyFile ?
    java.nio.file.Files.readString(java.nio.file.Path.of(privateKeyFile)) :
    privateKeyEnv
privateKeyText = privateKeyText.replaceAll('\\s', '')
def privateKeyBytes = Base64.decoder.decode(privateKeyText)
def keyFactory = KeyFactory.getInstance('RSA')
def privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes))
if (!(privateKey instanceof RSAPrivateCrtKey)) {
    throw new IllegalArgumentException('OIDC_PRIVATE_KEY must encode an RSA PKCS#8 CRT private key.')
}

def rsaPrivateKey = (RSAPrivateCrtKey) privateKey
def publicKey = keyFactory.generatePublic(
    new RSAPublicKeySpec(rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent())
)
def keyPair = new KeyPair(publicKey, privateKey)
def credentialId = 'jenkins-id-token'
def jenkins = Jenkins.get()
def store = CredentialsProvider.lookupStores(jenkins).find {
    it.getContext() == jenkins
}
if (store == null) {
    throw new IllegalStateException('Could not find the Jenkins system credentials store.')
}

def existing = store.getCredentials(Domain.global()).find {
    it.getId() == credentialId
}
if (existing != null && !(existing instanceof IdTokenFileCredentials)) {
    throw new IllegalStateException("Credential '${credentialId}' exists but is not an ID-token file credential.")
}
def credentialScope = existing == null ? CredentialsScope.GLOBAL : existing.getScope()
def credentialDescription = existing == null ? 'OIDC ID token with injected signing key' : existing.getDescription()
def credentialClass = IdTokenFileCredentials
def constructor = credentialClass.getDeclaredConstructor(
    CredentialsScope,
    String,
    String,
    KeyPair,
    Secret
)
constructor.setAccessible(true)

def credential = constructor.newInstance(
    credentialScope,
    credentialId,
    credentialDescription,
    keyPair,
    Secret.fromString(privateKeyText)
)
credential.setIssuer(issuerUrl)
if (existing != null) {
    credential.setAudience(existing.getAudience())
}
def saved
if (existing == null) {
    saved = store.addCredentials(Domain.global(), credential)
} else {
    saved = store.updateCredentials(Domain.global(), existing, credential)
}
if (!saved) {
    throw new IllegalStateException("Could not save OIDC credential '${credentialId}'.")
}

def keyFingerprint = MessageDigest.getInstance('SHA-256')
    .digest(publicKey.getEncoded())
    .encodeHex()
    .toString()
println "[OIDC-INIT] Saved credential '${credentialId}' for issuer '${issuerUrl}' (public-key SHA-256 ${keyFingerprint})."
