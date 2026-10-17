package net.antibs.client

/** Android's MainActivity companion state used by the shared UI (payment return deep link). */
object MainActivity {
    val paymentReturn = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
