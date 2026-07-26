package com.example.agenticanalytics.llmsim

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script.*
import io.circe.parser.parse

/**
 * Demonstration flow for agentic-analytics.
 *
 * It deliberately contains:
 *
 *   1. A successful end-to-end database workflow.
 *   2. A real PostgreSQL/MCP tool failure caused by querying a missing table.
 *   3. Script exhaustion after the two workflows have consumed all eight steps.
 *
 * The first two scenarios still use the real Spring AI application, MCP gateway,
 * database MCP server, and PostgreSQL data mart. Only the model is replaced by
 * llmsim.
 *
 * Run through scripts/e2e-demo.sh. The script leaves the containers running so
 * the complete call journal can be inspected in the llmsim console.
 */
object AnalyticsDemoFlow extends ScriptSource:

  /**
   * Extract the final non-empty line from the database MCP server's JSON result.
   *
   * The database tool reports a successful aggregate result approximately as:
   *
   *   [{"text":"SQL Query: ...\n\nResults (1 rows):\ncount\n-----\n6\n"}]
   */
  private def lastValue(mcpResult: String): String =
    val text =
      parse(mcpResult).toOption
        .flatMap(_.asArray)
        .flatMap(_.headOption)
        .flatMap(_.asObject)
        .flatMap(_("text"))
        .flatMap(_.asString)
        .getOrElse(mcpResult)

    text
      .split("\n")
      .map(_.trim)
      .filter(_.nonEmpty)
      .lastOption
      .getOrElse(text.trim)

  val script: Script =
    Script.exactly(
      // -----------------------------------------------------------------------
      // Scenario 1: successful database workflow
      // -----------------------------------------------------------------------
      toolCall(
        id = "success-list-tables",
        name = "list_tables",
        arguments = "{}"
      ),
      toolCall(
        id = "success-describe-merchant",
        name = "describe_table",
        arguments = """{"table_name":"merchant"}"""
      ),
      toolCall(
        id = "success-count-merchants",
        name = "execute_sql",
        arguments = """{"sql_query":"select count(*) from merchant"}"""
      ),
      replyFromToolResult("success-count-merchants") { result =>
        s"There are ${lastValue(result)} merchants."
      },

      // -----------------------------------------------------------------------
      // Scenario 2: real tool/database failure
      //
      // The SQL is deliberately invalid. It goes through the real MCP gateway
      // and reaches the real PostgreSQL database. The following model turn then
      // acknowledges the returned tool error rather than inventing a result.
      // -----------------------------------------------------------------------
      toolCall(
        id = "failure-list-tables",
        name = "list_tables",
        arguments = "{}"
      ),
      toolCall(
        id = "failure-describe-merchant",
        name = "describe_table",
        arguments = """{"table_name":"merchant"}"""
      ),
      toolCall(
        id = "failure-query-missing-table",
        name = "execute_sql",
        arguments =
          """{"sql_query":"select count(*) from merchant_missing_for_llmsim_demo"}"""
      ),
      replyFromToolResult("failure-query-missing-table") { _ =>
        "I could not complete the query because the database tool returned an error."
      }

      // A third question has no scripted step. Script.exactly therefore exposes
      // deterministic script exhaustion in llmsim and in its diagnostic console.
    )
