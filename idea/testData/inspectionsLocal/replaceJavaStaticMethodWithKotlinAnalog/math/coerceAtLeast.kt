// FIX: Replace with `coerceAtLeast` function
// WITH_RUNTIME
fun test(x: Double, y: Double) {
    <caret>Math.max(x, y)
}