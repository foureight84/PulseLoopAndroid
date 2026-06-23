package com.pulseloop.coach.tools

import kotlinx.serialization.json.*

/**
 * Ported from [ToolRegistry] in ToolRegistry.swift.
 * Assembles the enabled tool set for a turn.
 */
class ToolRegistry(private val flags: CoachFeatureFlags) {
    private val tools: Map<String, CoachToolDef>

    init {
        val all = RetrievalTools.all + AnalysisTools.all + ChartTools.all
        val writable = if (flags.writeToolsEnabled) MemoryTools.all + ActionTools.writeTools else emptyList()
        val live = if (flags.liveMeasurementsEnabled) ActionTools.measurementTools else emptyList()
        tools = (all + writable + live).associateBy { it.name }
    }

    fun tool(named: String): CoachToolDef? = tools[named]

    val toolSpecs: List<JsonObject>
        get() {
            val specs = tools.values.map { it.toolSpec }.toMutableList()
            if (flags.webSearchEnabled) specs.add(WebSearchTool.spec)
            return specs
        }

    private val CoachToolDef.toolSpec: JsonObject
        get() = JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "name" to JsonPrimitive(name),
                "description" to JsonPrimitive(description),
                "parameters" to parameters,
            ) + if (strict) mapOf("strict" to JsonPrimitive(true)) else emptyMap()
        )
}
