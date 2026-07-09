const fs = require('fs');
const code = fs.readFileSync('app/src/main/java/com/yourcompany/safebrowser/MainActivity.kt', 'utf8');
const htmlStart = code.indexOf('return """') + 10;
const htmlEnd = code.indexOf('""".trimIndent()', htmlStart);
let html = code.substring(htmlStart, htmlEnd);

const mockShortcuts = `
<a href='#' class='shortcut'><div class='shortcut-icon'>🌎</div>e-Library</a>
<a href='#' class='shortcut'><div class='shortcut-icon'>✉️</div>Gmail</a>
<a href='#' class='shortcut'><div class='shortcut-icon'>✨</div>Gemini</a>
<a href='#' class='shortcut'><div class='shortcut-icon'>🔍</div>Google</a>
`;

html = html.replace('${getAppLogoBase64()}', 'https://www.google.com/images/branding/chromelogo/2x/chromelogo_color_68x68dp.png');
html = html.replace(/\$\{if\s*\(shortcutsHtml.*else\s*""\}/g, `<div class="shortcuts-card">${mockShortcuts}</div>`);

fs.writeFileSync('ntp_preview.html', html.trim());
console.log('Done!');
