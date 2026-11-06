package net.antibs.client.data

/**
 * One-level nested subscription lists (Hiddify/Mahsa style): a body whose only meaningful lines are
 * http(s) subscription URLs (no share-links). Children are fetched once; nested lists inside children
 * are not followed.
 */
object NestedSubscriptions {
    const val MAX_CHILDREN = 20
    /** Hard cap per HTTP body read (bytes). */
    const val MAX_BODY_BYTES = 2_000_000
    const val CHILD_CONNECT_TIMEOUT_MS = 10_000
    const val CHILD_READ_TIMEOUT_MS = 15_000

    /** Fragment-free URL for requests and dedupe (keeps query; trims one trailing '/' on paths). */
    fun canonicalUrl(url: String): String {
        var u = url.trim()
        val hash = u.indexOf('#')
        if (hash >= 0) u = u.substring(0, hash).trim()
        if (u.endsWith("/")) {
            val scheme = u.indexOf("://")
            val pathStart = if (scheme >= 0) u.indexOf('/', scheme + 3) else -1
            if (pathStart >= 0 && pathStart < u.length - 1) u = u.trimEnd('/')
        }
        return u
    }

    fun requestUrl(url: String): String = canonicalUrl(url)

    /** `#Name` on the subscription URL — suggested title only when the remote has no name. */
    fun fragmentTitle(url: String): String? {
        val hash = url.indexOf('#')
        if (hash < 0) return null
        return LinkParser.pctDecode(url.substring(hash + 1).trim()).ifBlank { null }
    }

    /**
     * When every non-comment, non-empty line is an [ImportText.isSubscriptionUrl] and none parse as a
     * share-link/proxy host, returns those URLs in order. Otherwise null (not an exclusive nested list).
     */
    fun exclusiveChildUrls(body: String): List<String>? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        if (lines.isEmpty()) return null
        val urls = ArrayList<String>(lines.size)
        for (line in lines) {
            if (!ImportText.isSubscriptionUrl(line)) return null
            if (LinkParser.parse(line) != null) return null
            urls += line
        }
        return urls
    }

    /**
     * Dedupe by [canonicalUrl] (case-insensitive), drop self-references to [parentUrl], cap at [maxChildren].
     * Returned URLs are fragment-free request URLs.
     */
    fun selectChildren(parentUrl: String, children: List<String>, maxChildren: Int = MAX_CHILDREN): List<String> {
        val parentKey = canonicalUrl(parentUrl).lowercase()
        val seen = HashSet<String>()
        val out = ArrayList<String>(minOf(children.size, maxChildren.coerceAtLeast(0)))
        for (raw in children) {
            val req = requestUrl(raw)
            if (req.isEmpty()) continue
            if (!req.startsWith("http://", true) && !req.startsWith("https://", true)) continue
            val key = canonicalUrl(req).lowercase()
            if (key == parentKey) continue
            if (!seen.add(key)) continue
            out += req
            if (out.size >= maxChildren) break
        }
        return out
    }

    /** HTTP / body metadata only — URL #fragment is applied later without overwriting a user title. */
    fun resolveTitle(headers: SubscriptionParser.SubHeaders, body: String, subscriptionUrl: String = ""): String? =
        headers.title
            ?: SubscriptionParser.extractBodyMeta(body).second.title

    /**
     * Merge one-level child parses. Empty / failed children are simply absent from [childSubs].
     */
    fun merge(
        outerHeaders: SubscriptionParser.SubHeaders,
        outerBody: String,
        subscriptionUrl: String,
        childSubs: List<RemoteSubscription>,
    ): RemoteSubscription? {
        val servers = SubscriptionParser.uniqueIds(childSubs.flatMap { it.servers })
        if (servers.isEmpty()) return null
        val ui = outerHeaders.userInfo
        return RemoteSubscription(
            kind = "remnawave",
            title = resolveTitle(outerHeaders, outerBody, subscriptionUrl),
            description = "",
            upload = ui.upload,
            download = ui.download,
            total = ui.total,
            expireEpoch = ui.expire,
            trafficUsedMb = 0.0,
            trafficLimitMb = 0.0,
            servers = servers,
            announce = outerHeaders.announce,
            supportUrl = outerHeaders.supportUrl,
            webPageUrl = outerHeaders.webPageUrl,
            updateIntervalHours = outerHeaders.updateIntervalHours,
            routing = outerHeaders.routing,
            fileName = outerHeaders.fileName,
        )
    }

    /**
     * Pure expand: [fetchChild] must parse a child body **without** calling expand again (no 2nd level).
     * Returns null when the body is not an exclusive nested list or all children fail/empty.
     */
    fun expand(
        parentUrl: String,
        outerBody: String,
        outerHeaders: SubscriptionParser.SubHeaders,
        fetchChild: (childUrl: String) -> RemoteSubscription?,
        maxChildren: Int = MAX_CHILDREN,
    ): RemoteSubscription? {
        val raw = exclusiveChildUrls(outerBody) ?: return null
        val selected = selectChildren(parentUrl, raw, maxChildren)
        if (selected.isEmpty()) return null
        val childSubs = ArrayList<RemoteSubscription>(selected.size)
        for (childUrl in selected) {
            val child = try {
                fetchChild(childUrl)
            } catch (_: Exception) {
                null
            } ?: continue
            if (child.servers.isNotEmpty()) childSubs += child
        }
        return merge(outerHeaders, outerBody, parentUrl, childSubs)
    }
}
