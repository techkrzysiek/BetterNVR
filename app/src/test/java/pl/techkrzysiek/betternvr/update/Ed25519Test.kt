package pl.techkrzysiek.betternvr.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Ed25519Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun verify(key: String, message: ByteArray, signature: ByteArray, piece: Int = 4096): Boolean {
        val v = Ed25519Verifier(hex(key), signature)
        message.toList().chunked(piece).forEach { v.update(it.toByteArray()) }
        return v.verify()
    }

    // RFC 8032, section 7.1, tests 1–3
    private val rfc = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun acceptsRfc8032Vectors() {
        for ((key, msg, sig) in rfc) assertTrue(msg, verify(key, hex(msg), hex(sig)))
    }

    /** Signed with `openssl pkeyutl -sign -rawin` (as the release workflow does) using RFC test 1's key; fed in pieces. */
    @Test
    fun acceptsLongMessageFedInPieces() {
        val message = ByteArray(200_000) { ((it * 7 + 3) % 251).toByte() }
        val sig = hex("2bd4cb65f2f83728f297dcf1061e6e633f4c246ce146f076be1144aa8701f0f5d2bc3d2a6bf14a5b3364275c8d6ca790772c67247a42bd1e0d1db8bc02076301")
        for (piece in listOf(1000, 4096, 65536, 200_000)) assertTrue(verify(rfc[0].first, message, sig, piece))
        message[123_456] = (message[123_456] + 1).toByte()
        assertFalse(verify(rfc[0].first, message, sig))
    }

    @Test
    fun rejectsTampering() {
        val (key, msg, sig) = rfc[2]
        assertFalse("other message", verify(key, hex("af83"), hex(sig)))
        assertFalse("other key", verify(rfc[1].first, hex(msg), hex(sig)))
        for (bit in listOf(0, 100, 255, 256, 300, 511)) {
            val bad = hex(sig).also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
            assertFalse("signature bit $bit", verify(key, hex(msg), bad))
        }
        assertFalse("short signature", verify(key, hex(msg), hex(sig).copyOf(63)))
        assertFalse("long signature", verify(key, hex(msg), hex(sig) + 0.toByte()))
        assertFalse("empty signature", verify(key, hex(msg), ByteArray(0)))
    }

    /** S + L is the same point multiple, but RFC 8032 requires S < L (no malleable signatures). */
    @Test
    fun rejectsNonCanonicalS() {
        val (key, msg, sig) = rfc[1]
        val l = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        val s = BigInteger(1, hex(sig).copyOfRange(32, 64).reversedArray()) + l
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        assertFalse(verify(key, hex(msg), hex(sig).copyOfRange(0, 32) + sBytes))
    }
}
