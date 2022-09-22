package eu.kanade.tachiyomi.animeextension.en.zoro.utils

import app.cash.quickjs.QuickJs

object FindPassword {

    fun getPassword(js: String): String {
        val passVar = js.substringAfter("CryptoJS[")
            .substringBefore(");")
            .substringAfterLast(",")

        val passValue = js.substringAfter("const $passVar=", "").substringBefore(";", "")
        if (!passValue.isEmpty()) {
            if (passValue.startsWith("'"))
                return passValue.trim('\'')
            return getPasswordFromJS(js, "(" + passValue.substringAfter("("))
        }
        val jsEnd = js.substringBefore("jwplayer(").substringBeforeLast("var")
        val suspiciousPass = jsEnd.substringBeforeLast("'").substringAfterLast("'")
        if (suspiciousPass.length < 8) {
            // something like (0x420,'NZsZ')
            val funcArgs = jsEnd.substringAfterLast("(0x").substringBefore(")")
            return getPasswordFromJS(js, "(0x" + funcArgs + ")")
        }
        return suspiciousPass
    }

    private fun getPasswordFromJS(js: String, getKeyArgs: String): String {
        var script = js.substringBefore(",(!function") + ")"

        val decoderFunName = script.substringBefore(";(func").substringAfterLast("=")
        val decoderFunPrefix = "function " + decoderFunName
        var decoderFunBody = decoderFunPrefix + js.substringAfter(decoderFunPrefix)
        val decoderFunSuffix = ",$decoderFunName("
        val decoderFunCall = decoderFunSuffix + decoderFunBody
            .substringAfter(decoderFunSuffix)
            .substringBefore(");}") + ");}"
        decoderFunBody = decoderFunBody.substringBefore(decoderFunCall) + decoderFunCall
        // if it doesnt have the comically big list at the top, it must be
        // inside of a function.
        if ("=[" !in script.substring(0, 20)) {
            val superArrName = decoderFunBody.substringAfter("=").substringBefore(";")
            val superArrPrefix = "function " + superArrName
            val superArrSuffix = "return " + superArrName + ";}"
            val superArrBody = superArrPrefix + js.substringAfter(superArrPrefix)
                .substringBefore(superArrSuffix) + superArrSuffix
            script += "\n" + superArrBody + "\n"
        }

        script += "\n" + decoderFunBody
        script += "\n$decoderFunName$getKeyArgs"
        val qjs = QuickJs.create()
        // this part can be really slow, like 5s or even more >:(
        val result = qjs.evaluate(script).toString()
        qjs.close()
        return result
    }
}
