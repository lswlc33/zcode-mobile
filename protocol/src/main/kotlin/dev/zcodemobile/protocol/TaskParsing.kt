package dev.zcodemobile.protocol

/**
 * Parse `bootstrap-response.result.tasks` into a list that is safe to use as
 * a `LazyColumn` key source.
 *
 * Observed on the live desktop: the same `taskId` can appear twice, once as a
 * stale `completed` entry and once as the live `running` one. Duplicate keys
 * crash Compose, so the collapse happens here rather than in each client.
 */
fun parseTaskSummaries(raw: Any?): List<TaskSummary> =
    Json.asList(raw)
        .map { t ->
            val m = Json.asMap(t)
            TaskSummary(
                taskId = Json.asString(m["taskId"]) ?: "",
                title = Json.asString(m["title"]) ?: "",
                displayStatus = Json.asString(m["displayStatus"]),
                workspacePath = Json.asString(m["workspacePath"]),
                workspaceLabel = Json.asString(m["workspaceLabel"]),
                model = Json.asString(m["model"]),
                provider = Json.asString(m["provider"]),
                updatedAt = Json.asLong(m["updatedAt"]),
                unreadAt = Json.asLong(m["unreadAt"]),
                raw = m,
            )
        }
        .filter { it.taskId.isNotEmpty() }
        .groupBy { it.taskId }
        .map { (_, group) ->
            group.firstOrNull { it.displayStatus == "running" }
                ?: group.maxByOrNull { it.updatedAt ?: 0L }
                ?: group.first()
        }
        .sortedByDescending { it.updatedAt ?: 0L }
