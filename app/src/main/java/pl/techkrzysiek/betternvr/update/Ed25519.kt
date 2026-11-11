package pl.techkrzysiek.betternvr.update

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Checks an Ed25519 signature (RFC 8032, section 5.1.7) over a message fed in pieces, so a whole APK never has to
 * sit in memory. Android has no Ed25519 before API 33, hence this small verify-only version of the RFC's reference
 * code. It is not constant-time, which is fine: a signature check handles only public data.
 */
class Ed25519Verifier(private val publicKey: ByteArray, private val signature: ByteArray) {
    private val wellFormed = publicKey.size == 32 && signature.size == 64

    // the challenge is SHA-512(R ‖ A ‖ message)
    private val sha = MessageDigest.getInstance("SHA-512").apply {
        if (wellFormed) { update(signature, 0, 32); update(publicKey) }
    }

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) = sha.update(bytes, offset, length)

    fun verify(): Boolean {
        if (!wellFormed) return false
        val a = decompress(publicKey) ?: return false
        val r = decompress(signature.copyOfRange(0, 32)) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = littleEndian(sha.digest()).mod(L)
        return (BASE * s).sameAs(r + a * k)
    }

    /** A point in extended coordinates: x = X/Z, y = Y/Z, x·y = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        operator fun plus(o: Point): Point {
            val a = ((y - x) * (o.y - o.x)).p()
            val b = ((y + x) * (o.y + o.x)).p()
            val c = (TWO * t * o.t * D).p()
            val d = (TWO * z * o.z).p()
            val e = b - a
            val f = d - c
            val g = d + c
            val h = b + a
            return Point((e * f).p(), (g * h).p(), (f * g).p(), (e * h).p())
        }

        operator fun times(n: BigInteger): Point {
            var result = IDENTITY
            var addend = this
            for (i in 0 until n.bitLength()) {
                if (n.testBit(i)) result += addend
                addend += addend
            }
            return result
        }

        fun sameAs(o: Point) = (x * o.z - o.x * z).p().signum() == 0 && (y * o.z - o.y * z).p().signum() == 0
    }

    private companion object {
        val TWO: BigInteger = BigInteger.valueOf(2)
        val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        val D: BigInteger = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).p()
        val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)
        val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val BASE: Point = run {
            val y = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P)).p()
            val x = recoverX(y, false)!!
            Point(x, y, BigInteger.ONE, (x * y).p())
        }

        fun BigInteger.p(): BigInteger = mod(P)

        fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

        fun recoverX(y: BigInteger, odd: Boolean): BigInteger? {
            if (y >= P) return null
            val x2 = ((y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).modInverse(P)).p()
            if (x2.signum() == 0) return if (odd) null else BigInteger.ZERO
            var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
            if ((x * x - x2).p().signum() != 0) x = (x * SQRT_M1).p()
            if ((x * x - x2).p().signum() != 0) return null
            if (x.testBit(0) != odd) x = P - x
            return x
        }

        fun decompress(bytes: ByteArray): Point? {
            val v = littleEndian(bytes)
            val y = v.clearBit(255)
            val x = recoverX(y, v.testBit(255)) ?: return null
            return Point(x, y, BigInteger.ONE, (x * y).p())
        }
    }
}
