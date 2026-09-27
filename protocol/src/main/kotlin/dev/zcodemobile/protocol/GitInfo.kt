package dev.zcodemobile.protocol

data class GitBranch(
    val name: String,
    val isCurrent: Boolean,
    val upstream: String?,
)

/** Repository state for one workspace. */
data class GitRepoInfo(
    val isRepository: Boolean = false,
    val repoName: String? = null,
    val currentBranch: String? = null,
    val isDirty: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    val branches: List<GitBranch> = emptyList(),
) {
    /** Short label for the composer chip. */
    val branchLabel: String
        get() = currentBranch ?: "分离头指针"
}

/**
 * The `git` service, used by the composer to show which repository and branch
 * the session is operating on.
 *
 * The channel is reachable over the relay (verified against the live host);
 * workspaces that are not repositories answer `kind: "not-repository"` and the
 * UI hides the row rather than showing an empty branch picker.
 */
@ZCodeExperimental
class GitService(private val channel: ChannelClient) {

    companion object {
        const val CHANNEL = "git"
    }

    suspend fun info(workspacePath: String): GitRepoInfo {
        // `getWorkspaceRepositoryInfo` classifies the workspace's *position*
        // inside a repo and its `kind` is not a boolean: a normal checkout
        // reports `"main-tree"`, not `"repository"`. Gating on that string hid
        // the branch row for real repositories, so the authority is
        // `getRepositorySummary.isRepository` instead.
        val repo = runCatching {
            Json.asMap(
                channel.call(CHANNEL, "getRepositorySummary", linkedMapOf("workspacePath" to workspacePath))
            )
        }.getOrDefault(emptyMap())

        val isRepository = Json.asBool(repo["isRepository"])
            ?: Json.asString(
                Json.asMap(
                    runCatching {
                        channel.call(
                            CHANNEL,
                            "getWorkspaceRepositoryInfo",
                            linkedMapOf("workspacePath" to workspacePath),
                        )
                    }.getOrDefault(emptyMap<String, Any?>())
                )["kind"]
            )?.let { it != "not-repository" }
            ?: false

        if (!isRepository) return GitRepoInfo(isRepository = false)

        val branches = runCatching {
            Json.asMap(
                channel.call(CHANNEL, "getLocalBranches", linkedMapOf("workspacePath" to workspacePath))
            )
        }.getOrDefault(emptyMap())

        val branchList = Json.asList(branches["branches"]).map { b ->
            val m = Json.asMap(b)
            GitBranch(
                name = Json.asString(m["name"]) ?: "",
                isCurrent = Json.asBool(m["isCurrent"]) ?: false,
                upstream = Json.asString(m["upstreamName"]),
            )
        }.filter { it.name.isNotEmpty() }

        return GitRepoInfo(
            isRepository = true,
            repoName = Json.asString(repo["repoRoot"])?.let { root ->
                root.substringAfterLast('/').substringAfterLast('\\')
            },
            currentBranch = Json.asString(repo["branchName"])
                ?: Json.asString(branches["currentBranchName"]),
            isDirty = Json.asBool(repo["isDirty"]) ?: false,
            ahead = (Json.asLong(repo["ahead"]) ?: 0L).toInt(),
            behind = (Json.asLong(repo["behind"]) ?: 0L).toInt(),
            branches = branchList,
        )
    }

    /** Returns true when the switch actually happened. */
    suspend fun switchBranch(workspacePath: String, targetBranchName: String): Boolean {
        val result = Json.asMap(
            channel.call(
                CHANNEL,
                "switchBranch",
                linkedMapOf(
                    "workspacePath" to workspacePath,
                    "targetBranchName" to targetBranchName,
                ),
            )
        )
        return Json.asBool(result["ok"]) == true
    }
}
