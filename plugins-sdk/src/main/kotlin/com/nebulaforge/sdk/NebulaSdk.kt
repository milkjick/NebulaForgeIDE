package com.nebulaforge.sdk

import com.nebulaforge.core.mcp.McpToolDefinition
import com.nebulaforge.core.plugin.*
import org.json.JSONObject
interface AiToolProvider { fun definition():AiToolDefinition; suspend fun execute(arguments:JSONObject):JSONObject }
data class AiToolDefinition(val name:String,val description:String,val inputSchema:JSONObject)
interface McpServiceContributor { fun tools():List<McpToolDefinition>; suspend fun call(name:String,args:JSONObject):JSONObject }
interface NebulaSdkPlugin: NebulaPlugin { val apiVersion:Int get()=1 }
