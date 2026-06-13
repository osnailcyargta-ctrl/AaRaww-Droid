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
        for (lineNum in afterLineTriggers.keys.sorted()) {
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
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> { i++; continue }
                trimmed == "on start:" -> {
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val t = inner.trim()
                        if (t.isEmpty() || t.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        onStartBlock.add(t); i++
                    }
                }
                trimmed.matches(Regex("after line\\.\\d+( or line\\.\\d+)*:")) -> {
                    val nums = Regex("\\d+").findAll(trimmed).map { it.value.toInt() }.toList()
                    val block = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val t = inner.trim()
                        if (t.isEmpty() || t.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        block.add(t); i++
                    }
                    for (num in nums) afterLineTriggers.getOrPut(num) { mutableListOf() }.addAll(block)
                }
                trimmed == "after.all:" -> {
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        val t = inner.trim()
                        if (t.isEmpty() || t.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        afterAllBlock.add(t); i++
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
                        val t = inner.trim()
                        if (t.isEmpty() || t.startsWith("#")) { i++; continue }
                        if (!inner.startsWith(" ") && !inner.startsWith("\t")) break
                        funcLines.add(t); i++
                    }
                    functions[funcName] = Function(params, funcLines)
                }
                else -> i++
            }
        }
    }

    private fun executeBlock(lines: List<String>, localVars: MutableMap<String, Any> = mutableMapOf()) {
        var i = 0
        while (i < lines.size && !terminated) {
            i = executeStatement(lines, i, localVars)
        }
    }

    private fun executeStatement(lines: List<String>, index: Int, localVars: MutableMap<String, Any>): Int {
        val line = lines[index].trim()
        if (line.isEmpty() || line.startsWith("#")) return index + 1
        if (line == "end") { terminated = true; return index + 1 }

        if (line.startsWith("print.newline(") || line.startsWith("print.nextline(")) {
            output.append(extractArgs(line.substringAfter("(").substringBeforeLast(")"), localVars)).append("\n")
            return index + 1
        }
        if (line.startsWith("print(")) {
            output.append(extractArgs(line.substringAfter("(").substringBeforeLast(")"), localVars))
            return index + 1
        }

        if (line.startsWith("function.call ")) {
            val call = line.removePrefix("function.call ").trim()
            val funcName = call.substringBefore("(").trim()
            val argsStr = call.substringAfter("(").substringBefore(")")
            val argVals = if (argsStr.isBlank()) emptyList() else argsStr.split(",").map { evalExpr(it.trim(), localVars) }
            val func = functions[funcName]
            if (func != null) {
                val fl = mutableMapOf<String, Any>()
                fl.putAll(variables); fl.putAll(localVars)
                func.params.forEachIndexed { i, p -> if (i < argVals.size) fl[p] = argVals[i] }
                executeBlock(func.lines, fl)
            }
            return index + 1
        }

        if (line.startsWith("input.var\\")) {
            val rest = line.removePrefix("input.var\\").trim()
            val varName = rest.substringAfter("\"").substringBefore("\"")
            val promptRaw = rest.substringAfter("(")
            val prompt = promptRaw.substringAfter("'").substringBeforeLast("'")
                .ifEmpty { promptRaw.substringAfter("\"").substringBeforeLast("\"") }
            setVar(varName, inputHandler?.invoke(prompt) ?: "", localVars)
            return index + 1
        }

        // if / elif / else
        if (line.startsWith("if ") && line.endsWith(":")) {
            return executeIfChain(lines, index, localVars)
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
                if (isBlockStart(l)) depth++
                if (l == "end" && depth == 0) { whileBlock.add(l); i++; break }
                if (isBlockEnd(l) && depth > 0) depth--
                whileBlock.add(l); i++
            }
            var safety = 0
            while (evalCondition(condition, localVars) && !terminated) {
                executeBlock(whileBlock, localVars)
                if (++safety > 10000) { output.append("[ERROR: loop limit]\n"); break }
            }
            return i
        }

        val assignMatch = Regex("^\"([^\"]+)\"\\s*=\\s*(.+)$").find(line)
        if (assignMatch != null) {
            setVar(assignMatch.groupValues[1], evalExpr(assignMatch.groupValues[2].trim(), localVars), localVars)
            return index + 1
        }
        val plainAssign = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$").find(line)
        if (plainAssign != null) {
            localVars[plainAssign.groupValues[1]] = evalExpr(plainAssign.groupValues[2].trim(), localVars)
            return index + 1
        }

        return index + 1
    }

    // Handles if / elif* / else chain
    private fun executeIfChain(lines: List<String>, index: Int, localVars: MutableMap<String, Any>): Int {
        // Parse all branches: list of Pair(condition|null, block)
        // condition == null means else
        data class Branch(val condition: String?, val block: MutableList<String>)
        val branches = mutableListOf<Branch>()

        var i = index
        while (i < lines.size) {
            val l = lines[i].trim()
            val isIf = l.startsWith("if ") && l.endsWith(":")
            val isElif = l.startsWith("elif ") && l.endsWith(":")
            val isElse = l == "else:"

            if (!isIf && !isElif && !isElse) break

            val condition = when {
                isIf -> l.removePrefix("if ").removeSuffix(":")
                isElif -> l.removePrefix("elif ").removeSuffix(":")
                else -> null
            }

            val block = mutableListOf<String>()
            i++
            var depth = 0

            while (i < lines.size) {
                val bl = lines[i].trim()
                if (bl.isEmpty() || bl.startsWith("#")) { i++; continue }

                // Stop collecting this branch if we hit elif/else at depth 0
                if (depth == 0 && (bl.startsWith("elif ") || bl == "else:")) break
                // Stop if we hit something that's a new top-level block header (not nested)
                if (depth == 0 && isBlockStart(bl) && !bl.startsWith("if ") && !bl.startsWith("elif") && !bl.startsWith("while")) break

                if (isBlockStart(bl)) depth++
                if (bl == "end" && depth == 0) { block.add(bl); i++; break }
                if (isBlockEnd(bl) && depth > 0) depth--
                block.add(bl); i++
            }

            branches.add(Branch(condition, block))
            if (isElse) break
        }

        // Execute first matching branch
        var executed = false
        for (branch in branches) {
            if (!executed) {
                val matches = if (branch.condition == null) true else evalCondition(branch.condition, localVars)
                if (matches) {
                    executeBlock(branch.block, localVars)
                    executed = true
                }
            }
        }

        return i
    }

    private fun isBlockStart(l: String) = (l.startsWith("if ") && l.endsWith(":")) || (l.startsWith("while ") && l.endsWith(":"))
    private fun isBlockEnd(l: String) = l.endsWith(":")

    private fun evalCondition(condition: String, localVars: MutableMap<String, Any>): Boolean {
        val t = condition.trim()
        if (t == "true") return true
        if (t == "false") return false
        if (t.contains(" and ")) return t.split(" and ").all { evalCondition(it.trim(), localVars) }
        if (t.contains(" or ")) return t.split(" or ").any { evalCondition(it.trim(), localVars) }

        val varRef = Regex("\\(var\\\\\\s*\"([^\"]+)\"\\)\\s*(==|!=|>=|<=|>|<)\\s*(.+)").find(t)
        if (varRef != null) {
            return compare(getVar(varRef.groupValues[1], localVars), varRef.groupValues[2], evalExpr(varRef.groupValues[3].trim(), localVars))
        }
        val plain = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").find(t)
        if (plain != null) {
            return compare(localVars[plain.groupValues[1]] ?: variables[plain.groupValues[1]] ?: "", plain.groupValues[2], evalExpr(plain.groupValues[3].trim(), localVars))
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

        Regex("\\(var\\\\\\s*\"([^\"]+)\"\\)").find(t)?.let { return getVar(it.groupValues[1], localVars) }
        Regex("^var\\\\\\s*\"([^\"]+)\"$").find(t)?.let { return getVar(it.groupValues[1], localVars) }

        val arith = Regex("^(.+?)\\s*([+\\-*/])\\s*(.+)$").find(t)
        if (arith != null) {
            val left = evalExpr(arith.groupValues[1].trim(), localVars)
            val right = evalExpr(arith.groupValues[3].trim(), localVars)
            val l = toDouble(left); val r = toDouble(right)
            if (l != null && r != null) {
                val res = when (arith.groupValues[2]) { "+" -> l + r; "-" -> l - r; "*" -> l * r; "/" -> if (r != 0.0) l / r else 0.0; else -> 0.0 }
                return if (res == kotlin.math.floor(res)) res.toLong() else res
            }
            return left.toString() + right.toString()
        }
        if (t.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) return localVars[t] ?: variables[t] ?: t
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

    private fun getVar(name: String, localVars: MutableMap<String, Any>): Any = localVars[name] ?: variables[name] ?: ""
    private fun setVar(name: String, value: Any, localVars: MutableMap<String, Any>) {
        if (localVars.containsKey(name)) localVars[name] = value else variables[name] = value
    }
}
