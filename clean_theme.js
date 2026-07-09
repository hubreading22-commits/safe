const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/yourcompany/safebrowser/MainActivity.kt', 'utf8');

code = code.replace(/private var currentThemeColor.*?\n/g, '');
code = code.replace(/private var themeColorAnimator.*?\n/g, '');

const tcStart = code.indexOf('fun onThemeColor(');
if (tcStart !== -1) {
    const fnStart = code.lastIndexOf('@android.webkit.JavascriptInterface', tcStart);
    let openBraces = 0;
    let foundBrace = false;
    let endIdx = -1;
    for (let i = tcStart; i < code.length; i++) {
        if (code[i] === '{') { openBraces++; foundBrace = true; }
        if (code[i] === '}') { openBraces--; }
        if (foundBrace && openBraces === 0) { endIdx = i + 1; break; }
    }
    if (endIdx !== -1) {
        code = code.substring(0, fnStart) + code.substring(endIdx);
    }
}

const acStart = code.indexOf('private fun applyThemeColor(');
if (acStart !== -1) {
    let openBraces = 0;
    let foundBrace = false;
    let endIdx = -1;
    for (let i = acStart; i < code.length; i++) {
        if (code[i] === '{') { openBraces++; foundBrace = true; }
        if (code[i] === '}') { openBraces--; }
        if (foundBrace && openBraces === 0) { endIdx = i + 1; break; }
    }
    if (endIdx !== -1) {
        code = code.substring(0, acStart) + code.substring(endIdx);
    }
}

code = code.replace(/view\?\.evaluateJavascript\("""[\s\S]*?"""\.trimIndent\(\), null\)\n/g, '');

code = code.replace(/body\s*\{([\s\S]*?)min-height:\s*100vh;/g, 'body {$1min-height: 100vh; overflow: hidden;');

fs.writeFileSync('app/src/main/java/com/yourcompany/safebrowser/MainActivity.kt', code);
console.log('Script completed');
