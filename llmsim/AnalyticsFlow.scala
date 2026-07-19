package com.example.agenticanalytics.llmsim

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._
import io.circe.parser.parse

/** Mirrors the agent's actual required sequence (per its own system
  * prompt, captured in a call journal entry): list_tables, then
  * describe_table on whatever table matters, then execute_sql, THEN a
  * final reply -- not a single tool call. llmsim can't discover the
  * table name dynamically (it doesn't read tool results to decide its
  * own behavior, by design), so the table name below has to be known
  * and hardcoded by whoever writes the script, same as the SQL itself.
  *
  * Confirmed against the real schema via `\dt`: merchant, region,
  * transaction, vector_store. This script answers "How many merchants
  * do we have?"
  */
object AnalyticsFlow extends ScriptSource {

  // execute_sql's MCP tool reports its result as a JSON string shaped like
  //   [{"text": "SQL Query: ...\n\nResults (1 rows):\ncount\n-----\n6\n"}]
  // -- this pulls out just the last non-blank line, which is the actual
  // value for a single-aggregate query like COUNT(*). Specific to this
  // tool's report format, not a general MCP result parser.
  private def lastValue(mcpResult: String): String = {
    val text = parse(mcpResult).toOption
      .flatMap(_.asArray)
      .flatMap(_.headOption)
      .flatMap(_.asObject)
      .flatMap(_("text"))
      .flatMap(_.asString)
      .getOrElse(mcpResult)
    text.split("\n").map(_.trim).filter(_.nonEmpty).lastOption.getOrElse(text.trim)
  }

  val script: Script = Script.exactly(
    toolCall(id = "call-1", name = "list_tables", arguments = "{}"),
    toolCall(id = "call-2", name = "describe_table", arguments = """{"table_name":"merchant"}"""),
    toolCall(id = "call-3", name = "execute_sql", arguments = """{"sql_query":"select count(*) from merchant"}"""),
    replyFromToolResult("call-3")(result => s"There are ${lastValue(result)} merchants.")
  )
}
