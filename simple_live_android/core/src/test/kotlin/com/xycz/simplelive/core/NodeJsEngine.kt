package com.xycz.simplelive.core

import com.xycz.simplelive.core.common.JsEngine
import java.io.File

/** 单测用：调用本机 node 执行 JS（vm 上下文里执行，行为接近 QuickJS 的全局脚本） */
class NodeJsEngine : JsEngine {
    override fun evaluate(scripts: List<String>, expression: String): String {
        val dir = File(System.getProperty("java.io.tmpdir"), "nodejs-${System.nanoTime()}").apply { mkdirs() }
        try {
            scripts.forEachIndexed { i, s -> File(dir, "s$i.js").writeText(s) }
            File(dir, "expr.js").writeText(expression)
            val runner = File(dir, "run.js")
            runner.writeText(
                """
                const vm = require('vm'); const fs = require('fs'); const path = require('path');
                const ctx = vm.createContext({});
                for (let i = 0; i < ${scripts.size}; i++) vm.runInContext(fs.readFileSync(path.join(__dirname, 's' + i + '.js'), 'utf8'), ctx);
                process.stdout.write(String(vm.runInContext(fs.readFileSync(path.join(__dirname, 'expr.js'), 'utf8'), ctx)));
                """.trimIndent(),
            )
            val p = ProcessBuilder("node", runner.absolutePath).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw IllegalStateException("node 执行失败: $err")
            return out
        } finally {
            dir.deleteRecursively()
        }
    }
}
