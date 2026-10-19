import android.content.Context
import com.nekokeys.keyboard.*
import java.io.File
fun alpha11(i: Int): String { var n = i; val sb = StringBuilder("zq"); do { sb.append('a' + n % 26); n /= 26 } while (n > 0); return sb.toString() }
fun main() {
    val pr = Predictor::class.java.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }.newInstance(null)
    System.gc(); Thread.sleep(200); System.gc()
    val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    // fill both stores to their limits: 40k pairs, 5k words
    for (i in 0 until 1400) for (j in 0 until 29) pr.learn(alpha11(i), alpha11(100000 + i * 30 + j))
    System.gc(); Thread.sleep(300); System.gc()
    val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    println(String.format("learned data at its maximum (~%d pairs): ~%.1f MB heap", 1400 * 29, (after - before) / 1048576.0))
}
