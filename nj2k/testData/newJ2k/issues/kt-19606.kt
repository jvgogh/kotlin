import java.util.HashMap

class TestMethodReference {
    private val hashMap: HashMap<String, String> = HashMap()
    fun update(key: String, msg: String) {
        hashMap.merge(key, msg) { obj: String, s: String -> obj + s }
    }
}