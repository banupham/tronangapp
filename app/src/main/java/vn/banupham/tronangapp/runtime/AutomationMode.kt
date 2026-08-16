package vn.banupham.tronangapp.runtime

object AutomationMode {
    @Volatile
    var paused: Boolean = false
        private set

    fun setPaused(value: Boolean) {
        paused = value
    }
}
