package com.aaraww.droid

class AarRawInterpreter {

    data class Function(val params: List<String>, val lines: List<String>)

    private val variables = mutableMapOf<String, Any>()
    private val functions = mutableMapOf<String, Function>()
    private val output = StringBuilder()
    private var terminated = false

    private val allLines = mutableListOf<String>()
    private val afterLineTriggers = mutableMapOf<Int, MutableList<String>>()
    private val afterAllBlock = mutableListOf<String>()
    private val onStartBlock = mutableListOf<String>()

    var inputHandler: ((prompt: String) -> String)? = null

    fun interpret(code: String): String {
        reset()
        val rawLines = code.lines()
        rawLines.forEach { allLines.add(it) }
        parseTopLevel(rawLines)
        if (terminated) return output.toString()
        executeBlock(onStartBlock)
        if (terminated) return output.toString()
        val sortedTriggers = afterLineTriggers.keys.sorted()
        for (lineNum in sortedTriggers) {
            if (terminated) break
            executeBlock(afterLineTriggers[lineNum] ?: continue)
        }
        if (terminated) return output.toString()
        executeBlock(afterAllBlock)
        return output.toString()
    }

    private fun reset() {
        variables.clear(); functions.clear(); output.clear()
        terminated = false; allLines.clear(); afterLineTriggers.clear()
        afterAllBlock.clear(); onStartBlock.clear()
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
                    val params = if (paramsStr.isBlank()) emptyList() else paramsStr.split(",").map { it.trim() }
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

    // Block is a flat list of TRIMMED lines. We use a token-based if/else/while parser.
    private fun executeBlock(lines: List<String>, localVars: MutableMap<String, Any> = mutableMapOf()) {
        var i = 0
        while (i < lines.size && !terminated) {
            i = executeStatement(lines, i, localVars)
        }
    }

    // Returns next index after consuming statement (including nested blocks)
    private fun executeStatement(lines: List<String>, index: Int, localVars: MutableMap<String, Any>): Int {
        val line = lines[index].trim()
        if (line.isEmpty() || line.startsWith("#")) return index + 1

        if (line == "end") { terminated = true; return index + 1 }

        if (line.startsWith("print.newline(") || line.startsWith("print.nextline(")) {
            val args = extractArgs(line.substringAfter("(").substringBeforeLast(")"), localVars)
            output.append(args).append("\n")
            return index + 1
        }

        if (line.startsWith("print(")) {
            val args = extractArgs(line.substringAfter("(").substringBeforeLast(")"), localVars)
            output.append(args)
            return index + 1
        }

        if (line.startsWith("function.call ")) {
            val call = line.removePrefix("function.call ").trim()
            val funcName = call.substringBefore("(").trim()
            val argsStr = call.substringAfter("(").substringBefore(")")
            val argVals = if (argsStr.isBlank()) emptyList() else argsStr.split(",").map { evalExpr(it.trim(), localVars) }
            val func = functions[funcName]
            if (func != null) {
                val funcLocals = mutableMapOf<String, Any>()
                funcLocals.putAll(variables)
                funcLocals.putAll(localVars)
                func.params.forEachIndexed { i, param -> if (i < argVals.size) funcLocals[param] = argVals[i] }
                executeBlock(func.lines, funcLocals)
            }
            return index + 1
        }

        if (line.startsWith("input.var\\")) {
            val rest = line.removePrefix("input.var\\").trim()
            val varName = rest.substringAfter("\"").substringBefore("\"")
            val promptRaw = rest.substringAfter("(")
            val prompt = promptRaw.substringAfter("'").substringBeforeLast("'")
                .ifEmpty { promptRaw.substringAfter("\"").substringBeforeLast("\"") }
            val inputVal = inputHandler?.invoke(prompt) ?: ""
            setVar(varName, inputVal, localVars)
            return index + 1
        }

        // if statement — collect then/else blocks from flat list by tracking depth
        if (line.startsWith("if ") && line.endsWith(":")) {
            val condition = line.removePrefix("if ").removeSuffix(":")
            // Collect body lines until else: or end of block (no indent tracking needed, flat list)
            // We use a depth counter for nested if/while
            val ifBlock = mutableListOf<String>()
            val elseBlock = mutableListOf<String>()
            var i = index + 1
            var depth = 0
            var inElse = false

            while (i < lines.size) {
                val l = lines[i].trim()
                if (l.isEmpty() || l.startsWith("#")) { i++; continue }

                // Track nesting
                if ((l.startsWith("if ") && l.endsWith(":")) || l.startsWith("while ")) depth++

                if (depth == 0 && l == "else:") {
                    inElse = true
                    i++
                    continue
                }

                // End of if block: hit something that's not nested and not else
                if (depth == 0 && !inElse && !l.startsWith("if ") && !l.startsWith("while ") &&
                    !l.startsWith("print") && !l.startsWith("input") &&
                    !l.startsWith("function") && !l.startsWith("\"") &&
                    !l.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*\\s*=.*")) &&
                    l != "end" && l != "else:") {
                    break
                }

                if (l == "end" && depth == 0) {
                    if (inElse) elseBlock.add(l) else ifBlock.add(l)
                    i++
                    break
                }

                if (l.endsWith(":") && depth > 0) depth--

                if (inElse) elseBlock.add(l) else ifBlock.add(l)
                i++
            }

            val condResult = evalCondition(condition, localVars)
            if (condResult) executeBlock(ifBlock, localVars)
            else if (elseBlock.isNotEmpty()) executeBlock(elseBlock, localVars)
            return i
        }

        // while
        if (line.startsWith("while ") && line.endsWith(":")) {
            val condition = line.removePrefix("while ").removeSuffix(":")
            val whileBlock = mutableListOf<String>()
            var i = index + 1
            var depth = 0

            while (i < lines.size) {
                val l = lines[i].trim()
                if (l.isEmpty() || l.startsWith("#")) { i++; continue }
                if ((l.startsWith("if ") && l.endsWith(":")) || l.startsWith("while ")) depth++
                if (l == "end" && depth == 0) { whileBlock.add(l); i++; break }
                if (l.endsWith(":") && depth > 0) depth--
                whileBlock.add(l)
                i++
            }

            var safety = 0
            while (evalCondition(condition, localVars) && !terminated) {
                executeBlock(whileBlock, localVars)
                if (++safety > 10000) { output.append("[ERROR: loop limit reached]\n"); break }
            }
            return i
        }

        // variable assignment "name" = expr
        val assignMatch = Regex("^\"([^\"]+)\"\\s*=\\s*(.+)$").find(line)
        if (assignMatch != null) {
            val varName = assignMatch.groupValues[1]
            val value = evalExpr(assignMatch.groupValues[2].trim(), localVars)
            setVar(varName, value, localVars)
            return index + 1
        }

        // plain assignment (function locals)
        val plainAssign = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$").find(line)
        if (plainAssign != null) {
            val varName = plainAssign.groupValues[1]
            val value = evalExpr(plainAssign.groupValues[2].trim(), localVars)
            localVars[varName] = value
            return index + 1
        }

        return index + 1
    }

    private fun evalCondition(condition: String, localVars: MutableMap<String, Any>): Boolean {
        val t = condition.trim()
        if (t == "true") return true
        if (t == "false") return false

        if (t.contains(" and ")) return t.split(" and ").all { evalCondition(it.trim(), localVars) }
        if (t.contains(" or ")) return t.split(" or ").any { evalCondition(it.trim(), localVars) }

        val varRefMatch = Regex("\\(var\\\\\\s*\"([^\"]+)\"\\)\\s*(==|!=|>=|<=|>|<)\\s*(.+)").find(t)
        if (varRefMatch != null) {
            val left = getVar(varRefMatch.groupValues[1], localVars)
            val op = varRefMatch.groupValues[2]
            val right = evalExpr(varRefMatch.groupValues[3].trim(), localVars)
            return compare(left, op, right)
        }

        val plainMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").find(t)
        if (plainMatch != null) {
            val left = localVars[plainMatch.groupValues[1]] ?: variables[plainMatch.groupValues[1]] ?: ""
            val op = plainMatch.groupValues[2]
            val right = evalExpr(plainMatch.groupValues[3].trim(), localVars)
            return compare(left, op, right)
        }
        return false
    }

    private fun compare(left: Any, op: String, right: Any): Boolean {
        val l = toDouble(left); val r = toDouble(right)
        if (l != null && r != null) return when (op) {
            "==" -> l == r; "!=" -> l != r; ">=" -> l >= r
            "<=" -> l <= r; ">" -> l > r; "<" -> l < r; else -> false
        }
        return when (op) { "==" -> left.toString() == right.toString(); "!=" -> left.toString() != right.toString(); else -> false }
    }

    private fun toDouble(v: Any): Double? = when (v) {
        is Double -> v; is Int -> v.toDouble(); is Long -> v.toDouble()
        is String -> v.toDoubleOrNull(); else -> null
    }

    private fun evalExpr(expr: String, localVars: MutableMap<String, Any>): Any {
        val t = expr.trim()
        if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\"")))
            return t.substring(1, t.length - 1)
        t.toDoubleOrNull()?.let { return if (it == kotlin.math.floor(it)) it.toLong() else it }

        val varRef = Regex("\\(var\\\\\\s*\"([^\"]+)\"\\)").find(t)
        if (varRef != null) return getVar(varRef.groupValues[1], localVars)

        // bare var\ "name" without parens
        val bareVarRef = Regex("^var\\\\\\s*\"([^\"]+)\"$").find(t)
        if (bareVarRef != null) return getVar(bareVarRef.groupValues[1], localVars)

        // arithmetic
        val arithMatch = Regex("^(.+?)\\s*([+\\-*/])\\s*(.+)$").find(t)
        if (arithMatch != null) {
            val left = evalExpr(arithMatch.groupValues[1].trim(), localVars)
            val op = arithMatch.groupValues[2]
            val right = evalExpr(arithMatch.groupValues[3].trim(), localVars)
            val l = toDouble(left); val r = toDouble(right)
            if (l != null && r != null) {
                val res = when (op) { "+" -> l + r; "-" -> l - r; "*" -> l * r; "/" -> if (r != 0.0) l / r else 0.0; else -> 0.0 }
                return if (res == kotlin.math.floor(res)) res.toLong() else res
            }
            return left.toString() + right.toString()
        }

        if (t.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
            return localVars[t] ?: variables[t] ?: t

        return t
    }

    private fun extractArgs(argsStr: String, localVars: MutableMap<String, Any>): String =
        splitArgs(argsStr).joinToString("") { evalExpr(it.trim(), localVars).toString() }

    private fun splitArgs(s: String): List<String> {
        val result = mutableListOf<String>(); var current = StringBuilder()
        var inSingle = false; var inDouble = false; var depth = 0
        for (ch in s) {
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '(' && !inSingle && !inDouble -> depth++
                ch == ')' && !inSingle && !inDouble -> depth--
                ch == ',' && !inSingle && !inDouble && depth == 0 -> { result.add(current.toString()); current = StringBuilder(); continue }
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun getVar(name: String, localVars: MutableMap<String, Any>): Any =
        localVars[name] ?: variables[name] ?: ""

    private fun setVar(name: String, value: Any, localVars: MutableMap<String, Any>) {
        if (localVars.containsKey(name)) localVars[name] = value else variables[name] = value
    }
}
