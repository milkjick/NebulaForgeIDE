package com.nebulaforge.core.agent

import com.nebulaforge.core.projectmodel.BuildError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File

/**
 * Deterministic fix/rebuild coordinator. It never writes files itself: the caller
 * decides when a reviewed proposal is committed, then supplies the next build result.
 */
class BuildFixLoop(private val agent: BuildFixAgent, private val maxRounds: Int = 3) {
    data class Round(val number: Int, val errors: List<BuildError>, val proposal: BuildFixProposal?)

    suspend fun analyzeUntilResolved(
        projectRoot: File,
        initialErrors: List<BuildError>,
        rebuild: suspend () -> List<BuildError>,
        applyReviewed: suspend (BuildFixProposal) -> Boolean
    ): List<Round> {
        require(maxRounds in 1..5)
        var errors = initialErrors
        val rounds = mutableListOf<Round>()
        val signatures = mutableSetOf<String>()
        for (round in 1..maxRounds) {
            coroutineContext.ensureActive()
            if (errors.isEmpty()) break
            val signature = errors.joinToString("|") { "${it.filePath}:${it.line}:${it.column}:${it.message}" }
            if (!signatures.add(signature)) break
            val proposal = try { agent.propose(projectRoot, errors) } catch (e: CancellationException) { throw e }
            rounds += Round(round, errors, proposal)
            if (proposal.changes.isEmpty()) break
            if (!applyReviewed(proposal)) break
            errors = rebuild()
        }
        return rounds
    }
}
