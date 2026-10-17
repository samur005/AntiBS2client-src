package net.antibs.client.desktop

import kotlinx.coroutines.runBlocking
import net.antibs.client.engine.AppStore
import net.antibs.client.engine.SubscriptionSync
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Not a real test: seeds a demo profile for screenshots when ANTIBS_SEED_SUB is set (skipped otherwise). */
class DemoSeed {
    @Test fun seed(): Unit = runBlocking {
        val url = System.getenv("ANTIBS_SEED_SUB"); assumeTrue(url != null)
        val store = AppStore(android.app.Application.instance)
        store.update { it.copy(bypassLan = false, adv = it.adv.copy(mode = "proxy", pingUrl = "http://127.0.0.1:21080/generate_204")) }
        val ids = url!!.split(',').map { u -> store.addSubscription(u.trim()).also { println("seed: " + SubscriptionSync.fetchAndApply(store, it)?.result?.isSuccess) } }
        System.getenv("ANTIBS_SEED_MANUAL")?.split(';')?.filter { it.isNotBlank() }?.forEach { link ->
            net.antibs.client.data.LinkParser.parse(link)?.xray?.let { store.addManualProfile(it) }
        }
        store.servers().firstOrNull { it.subscriptionId == ids.first() }?.let { store.selectServer(it.id) }; Unit
    }
}
