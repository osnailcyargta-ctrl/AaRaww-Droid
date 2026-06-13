package com.aaraww.droid

/**
 * AarRaw Language Interpreter
 * Supports:
 * - on start: block
 * - after line.N: / after line.N or line.M: blocks
 * - after.all: block
 * - function.create name(params): / function.call name(args)
 * - print('...') / print.newline('...')
 * - "varname" = value  (declare/assign)
 * - if (var\ "name") op value: / else:
 * - while condition:
 * - input.var\ "name"('prompt')
 * - end
 * - # comments
 */
class AarRawInterpreter {

    data class Function(val params: List<String>, val lines: List<String>)

    private val variables = mutableMapOf<String, Any>()
    private val functions = mutableMapOf<String, Function>()
    private val output = StringBuilder()
    private var terminated = false

    // Lines as indexed list (1-based for after line.N)
    private val allLines = mutableListOf<String>()

    // afterLine triggers: lineNumber -> list of block lines
    private val afterLineTriggers = mutableMapOf<Int, MutableList<String>>()

    // afterAll block
    private val afterAllBlock = mutableListOf<String>()

    // on start block
    private val onStartBlock = mutableListOf<String>()

    // Input handler (for Android UI interaction)
    var inputHandler: ((prompt: String) -> String)? = null

    fun interpret(code: String): String {
        reset()
        val rawLines = code.lines()

        // Index all lines (1-based)
        rawLines.forEachIndexed { i, line ->
            allLines.add(line)
        }

        // Parse top-level blocks
        parseTopLevel(rawLines)

        if (terminated) return output.toString()

        // Execute on start
        executeBlock(onStartBlock)

        if (terminated) return output.toString()

        // Execute after line triggers in order
        val sortedTriggers = afterLineTriggers.keys.sorted()
        for (lineNum in sortedTriggers) {
            if (terminated) break
            executeBlock(afterLineTriggers[lineNum] ?: continue)
        }

        if (terminated) return output.toString()

        // Execute after.all
        executeBlock(afterAllBlock)

        return output.toString()
    }

    private fun reset() {
        variables.clear()
        functions.clear()
        output.clear()
        terminated = false
        allLines.clear()
        afterLineTriggers.clear()
        afterAllBlock.clear()
        onStartBlock.clear()
    }

    private fun parseTopLevel(lines: List<String>) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val trimmed = line.trim()

            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> { i++; continue }

                trimmed == "on start:" -> {
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val innerTrimmed = inner.trim()
                        if (innerTrimmed.isEmpty() || innerTrimmed.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        onStartBlock.add(innerTrimmed)
                        i++
                    }
                }

                trimmed.matches(Regex("after line\\.\\d+( or line\\.\\d+)*:")) -> {
                    // Extract all line numbers
                    val nums = Regex("\\d+").findAll(trimmed).map { it.value.toInt() }.toList()
                    val blockLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val innerTrimmed = inner.trim()
                        if (innerTrimmed.isEmpty() || innerTrimmed.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        blockLines.add(innerTrimmed)
                        i++
                    }
                    for (num in nums) {
                        afterLineTriggers.getOrPut(num) { mutableListOf() }.addAll(blockLines)
                    }
                }

                trimmed == "after.all:" -> {
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val innerTrimmed = inner.trim()
                        if (innerTrimmed.isEmpty() || innerTrimmed.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        afterAllBlock.add(innerTrimmed)
                        i++
                    }
                }

                trimmed.startsWith("function.create ") -> {
                    val header = trimmed.removePrefix("function.create ").removeSuffix(":")
                    val funcName = header.substringBefore("(").trim()
                    val paramsStr = header.substringAfter("(").substringBefore(")")
                    val params = if (paramsStr.isBlank()) emptyList()
                    else paramsStr.split(",").map { it.trim() }

                    val funcLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val innerTrimmed = inner.trim()
                        if (innerTrimmed.isEmpty() || innerTrimmed.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        funcLines.add(innerTrimmed)
                        i++
                    }
                    functions[funcName] = Function(params, funcLines)
                }

                else -> i++
            }
        }
    }

    private fun executeBlock(lines: List<String>, localVars: MutableMap<String, Any> = mutableMapOf()) {
        var i = 0
        while (i < lines.size) {
            if (terminated) return
            val line = lines[i]
            i = executeLine(lines, i, localVars)
        }
    }

    // Returns next index to process
    private fun executeLine(lines: List<String>, index: Int, localVars: MutableMap<String, Any>): Int {
        val line = lines[index].trim()

        if (line.isEmpty() || line.startsWith("#")) return index + 1

        // end
        if (line == "end") {
            terminated = true
            return index + 1
        }

        // print.newline(...)
        if (line.startsWith("print.newline(") || line.startsWith("print.nextline(")) {
            val args = extractArgs(line.substringAfter("(").substringBeforeLast(")"), localVars)
            output.append(args).append("\n")
            return index + 1
        }

        // print(...)
        if (line.startsWith("print(")) {
            val args = extractArgs(line.substringAfter("(").substringBeforeLast(")"), localVars)
            output.append(args)
            return index + 1
        }

        // function.call name(args)
        if (line.startsWith("function.call ")) {
            val call = line.removePrefix("function.call ").trim()
            val funcName = call.substringBefore("(").trim()
            val argsStr = call.substringAfter("(").substringBefore(")")
            val argVals = if (argsStr.isBlank()) emptyList()
            else argsStr.split(",").map { evalExpr(it.trim(), localVars) }

            val func = functions[funcName]
            if (func != null) {
                val funcLocals = mutableMapOf<String, Any>()
                funcLocals.putAll(localVars)
                funcLocals.putAll(variables)
                func.params.forEachIndexed { i, param ->
                    if (i < argVals.size) funcLocals[param] = argVals[i]
                }
                executeBlock(func.lines, funcLocals)
            }
            return index + 1
        }

        // input.var\ "varname"('prompt')
        if (line.startsWith("input.var\\")) {
            val rest = line.removePrefix("input.var\\").trim()
            val varName = rest.substringAfter("\"").substringBefore("\"")
            val prompt = rest.substringAfter("('").substringBefore("')")
                .ifEmpty { rest.substringAfter("(\"").substringBefore("\")") }
            val inputVal = inputHandler?.invoke(prompt) ?: ""
            setVar(varName, inputVal, localVars)
            return index + 1
        }

        // if (var\ "name") op value:
        if (line.startsWith("if ")) {
            return executeIf(lines, index, localVars)
        }

        // while condition:
        if (line.startsWith("while ")) {
            return executeWhile(lines, index, localVars)
        }

        // variable assignment: "varname" = expr
        val assignMatch = Regex("^\"([^\"]+)\"\\s*=\\s*(.+)$").find(line)
        if (assignMatch != null) {
            val varName = assignMatch.groupValues[1]
            val expr = assignMatch.groupValues[2].trim()
            val value = evalExpr(expr, localVars)
            setVar(varName, value, localVars)
            return index + 1
        }

        // plain variable assignment (inside function params): varname = expr
        val plainAssign = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$").find(line)
        if (plainAssign != null) {
            val varName = plainAssign.groupValues[1]
            val expr = plainAssign.groupValues[2].trim()
            val value = evalExpr(expr, localVars)
            localVars[varName] = value
            return index + 1
        }

        return index + 1
    }

    private fun executeIf(lines: List<String>, index: Int, localVars: MutableMap<String, Any>): Int {
        val line = lines[index].trim()
        val condition = line.removePrefix("if ").removeSuffix(":")

        val condResult = evalCondition(condition, localVars)

        // Collect if-block and else-block
        val ifBlock = mutableListOf<String>()
        val elseBlock = mutableListOf<String>()
        var i = index + 1
        var inElse = false

        while (i < lines.size) {
            val inner = lines[i].trim()
            if (inner.isEmpty() || inner.startsWith("#")) { i++; continue }

            // Check indentation - if line is not indented relative to if, stop
            val rawLine = lines[i]
            val isIndented = rawLine.startsWith("    ") || rawLine.startsWith("\t")

            if (!isIndented) break

            if (inner == "else:") {
                inElse = true
                i++
                continue
            }

            if (inElse) elseBlock.add(inner)
            else ifBlock.add(inner)
            i++
        }

        if (condResult) executeBlock(ifBlock, localVars)
        else if (elseBlock.isNotEmpty()) executeBlock(elseBlock, localVars)

        return i
    }

    private fun executeWhile(lines: List<String>, index: Int, localVars: MutableMap<String, Any>): Int {
        val line = lines[index].trim()
        val condition = line.removePrefix("while ").removeSuffix(":")

        // Collect while block
        val whileBlock = mutableListOf<String>()
        var i = index + 1

        while (i < lines.size) {
            val rawLine = lines[i]
            val inner = rawLine.trim()
            if (inner.isEmpty() || inner.startsWith("#")) { i++; continue }
            val isIndented = rawLine.startsWith("    ") || rawLine.startsWith("\t")
            if (!isIndented) break
            whileBlock.add(inner)
            i++
        }

        var safetyCounter = 0
        while (evalCondition(condition, localVars) && !terminated) {
            executeBlock(whileBlock, localVars)
            safetyCounter++
            if (safetyCounter > 10000) {
                output.append("[ERROR: Infinite loop detected, stopped at 10000 iterations]\n")
                break
            }
        }

        return i
    }

    private fun evalCondition(condition: String, localVars: MutableMap<String, Any>): Boolean {
        val trimmed = condition.trim()

        // true / false literals
        if (trimmed == "true") return true
        if (trimmed == "false") return false

        // and / or
        if (trimmed.contains(" and ")) {
            val parts = trimmed.split(" and ")
            return parts.all { evalCondition(it.trim(), localVars) }
        }
        if (trimmed.contains(" or ")) {
            val parts = trimmed.split(" or ")
            return parts.any { evalCondition(it.trim(), localVars) }
        }

        // (var\ "name") op value  OR  varname op value
        val varRefMatch = Regex("\\(var\\\\\\s*\"([^\"]+)\"\\)\\s*(==|!=|>=|<=|>|<)\\s*(.+)").find(trimmed)
        if (varRefMatch != null) {
            val varName = varRefMatch.groupValues[1]
            val op = varRefMatch.groupValues[2]
            val rhs = varRefMatch.groupValues[3].trim()
            val left = getVar(varName, localVars)
            val right = evalExpr(rhs, localVars)
            return compare(left, op, right)
        }

        // plain: varname op value
        val plainMatch = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)").find(trimmed)
        if (plainMatch != null) {
            val varName = plainMatch.groupValues[1]
            val op = plainMatch.groupValues[2]
            val rhs = plainMatch.groupValues[3].trim()
            val left = localVars[varName] ?: variables[varName] ?: ""
            val right = evalExpr(rhs, localVars)
            return compare(left, op, right)
        }

        return false
    }

    private fun compare(left: Any, op: String, right: Any): Boolean {
        // Try numeric comparison
        val l = toDouble(left)
        val r = toDouble(right)
        if (l != null && r != null) {
            return when (op) {
                "==" -> l == r
                "!=" -> l != r
                ">=" -> l >= r
                "<=" -> l <= r
                ">" -> l > r
                "<" -> l < r
                else -> false
            }
        }
        // String comparison
        val ls = left.toString()
        val rs = right.toString()
        return when (op) {
            "==" -> ls == rs
            "!=" -> ls != rs
            else -> false
        }
    }

    private fun toDouble(v: Any): Double? = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun evalExpr(expr: String, localVars: MutableMap<String, Any>): Any {
        val trimmed = expr.trim()

        // String literal
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length - 1)
        }

        // Number
        trimmed.toDoubleOrNull()?.let {
            return if (it == kotlin.math.floor(it)) it.toLong() else it
        }

        // var\ "name" reference
        val varRef = Regex("\\(var\\\\\\s*\"([^\"]+)\"\\)").find(trimmed)
        if (varRef != null) {
            return getVar(varRef.groupValues[1], localVars)
        }

        // Arithmetic: try to evaluate (var\ "x") + something or num + num
        // Handle expressions like (var\ "coins") + 1
        val arithMatch = Regex("(.+?)\\s*([+\\-*/])\\s*(.+)").find(trimmed)
        if (arithMatch != null) {
            val left = evalExpr(arithMatch.groupValues[1].trim(), localVars)
            val op = arithMatch.groupValues[2]
            val right = evalExpr(arithMatch.groupValues[3].trim(), localVars)
            val l = toDouble(left)
            val r = toDouble(right)
            if (l != null && r != null) {
                val result = when (op) {
                    "+" -> l + r
                    "-" -> l - r
                    "*" -> l * r
                    "/" -> if (r != 0.0) l / r else 0.0
                    else -> 0.0
                }
                return if (result == kotlin.math.floor(result)) result.toLong() else result
            }
            // String concat
            return left.toString() + right.toString()
        }

        // Plain variable name (function params)
        if (trimmed.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
            return localVars[trimmed] ?: variables[trimmed] ?: trimmed
        }

        return trimmed
    }

    private fun extractArgs(argsStr: String, localVars: MutableMap<String, Any>): String {
        // Split by comma but not inside quotes
        val parts = splitArgs(argsStr)
        return parts.joinToString("") { evalExpr(it.trim(), localVars).toString() }
    }

    private fun splitArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var depth = 0
        for (ch in s) {
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '(' && !inSingle && !inDouble -> depth++
                ch == ')' && !inSingle && !inDouble -> depth--
                ch == ',' && !inSingle && !inDouble && depth == 0 -> {
                    result.add(current.toString())
                    current = StringBuilder()
                    continue
                }
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun getVar(name: String, localVars: MutableMap<String, Any>): Any {
        return localVars[name] ?: variables[name] ?: ""
    }

    private fun setVar(name: String, value: Any, localVars: MutableMap<String, Any>) {
        // If already in localVars, update there; otherwise set global
        if (localVars.containsKey(name)) localVars[name] = value
        else variables[name] = value
    }
}
