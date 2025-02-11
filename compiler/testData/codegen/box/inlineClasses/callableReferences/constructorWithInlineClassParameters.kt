// WITH_STDLIB
import kotlin.test.assertEquals

@Suppress("OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")
@kotlin.jvm.JvmInline
value class Z(val x: Int)

class Outer(val z1: Z) {
    inner class Inner(val z2: Z)
}

fun box(): String {
    assertEquals(Z(1), ::Outer.invoke(Z(1)).z1)
    assertEquals(Z(2), Outer::Inner.invoke(Outer(Z(1)), Z(2)).z2)

    return "OK"
}