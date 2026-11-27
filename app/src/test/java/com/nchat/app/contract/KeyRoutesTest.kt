package com.nchat.app.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app and the backend are built from this one repository by two
 * toolchains that never meet: nothing compiles the address the app asks for
 * against the routes the backend has.
 *
 * For most routes a wrong address shows at once — the thing does not work.
 * For the replacement of the signed prekey it would not. "Not found" is what
 * a backend answers that is older than the app, and the app then carries on
 * with the key it has, quietly, for good. So the two source files are read
 * here, as text, and compared.
 */
class KeyRoutesTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "worker/src/routes/keys.ts").isFile }

    private val app: String = File(root, "app/src/main/java/com/nchat/app/network/NChatApi.kt").readText()
    private val backend: String = File(root, "worker/src/routes/keys.ts").readText()

    /** Method and address of every request the app makes under /keys, up to the first variable part. */
    private fun asked(): Set<String> = Regex("""client\.(get|post)\(\s*"(/keys/[a-z-]+)""").findAll(app)
        .map { it.groupValues[1].uppercase() + " " + it.groupValues[2] }.toSet()

    /** The same for every route the backend has there. */
    private fun served(): Set<String> {
        assertTrue(File(root, "worker/src/index.ts").readText().contains("app.route('/keys', keyRoutes)"))
        return Regex("""keys\.(get|post)\('(/[a-z-]+)""").findAll(backend)
            .map { it.groupValues[1].uppercase() + " /keys" + it.groupValues[2] }.toSet()
    }

    @Test
    fun everyKeyRouteTheAppAsksForIsOneTheBackendHas() {
        val all = setOf("POST /keys/register", "GET /keys/bundle", "GET /keys/prekeys", "POST /keys/prekeys", "POST /keys/signed-prekey")
        assertEquals(all, asked())
        assertEquals(all, served())
    }

    @Test
    fun theReplacementIsSentUnderTheNamesTheBackendReads() {
        val names = setOf("identity_public_key", "signed_prekey_public", "signed_prekey_signature")

        val request = app.substringAfter("\"/keys/signed-prekey\"", "").substringBefore("\n    }\n")
        assertEquals(names, Regex("""put\("([a-z_]+)"""").findAll(request).map { it.groupValues[1] }.toSet())

        val route = backend.substringAfter("keys.post('/signed-prekey'", "").substringBefore("\n});\n")
        assertEquals(names, Regex("""body\.([a-z_]+)""").findAll(route).map { it.groupValues[1] }.toSet())
    }
}
