const fs = require('fs');

let code = fs.readFileSync('c:/files/SafeBrowser/worker.js', 'utf8');

const regex = /<form method="POST" action="\/admin\/action">\r?\n\s*<input type="hidden" name="action" value="add_domains">\r?\n\s*<div class="domain-list">\$\{pendingList\}<\/div>\r?\n\s*<div class="action-bar"><button type="submit" class="btn btn-success">✓ Add Selected to Blocklist<\/button><\/div>\r?\n\s*<\/form>\r?\n\s*<form method="POST" action="\/admin\/action" style="margin-top:10px">\r?\n\s*<input type="hidden" name="action" value="ignore_domains">\r?\n\s*\$\{filteredPending\.map\(\(d\) => `<input type="hidden" name="domains" value="\$\{escapeHtml\(d\)\}">`\)\.join\(''\)\}\r?\n\s*<button type="submit" class="btn btn-secondary">🗑 Ignore All<\/button>\r?\n\s*<\/form>/;

const newForm = `<form method="POST" action="/admin/action">
            <div class="domain-list">\${pendingList}</div>
            <div class="action-bar" style="display:flex; gap:10px;">
              <button type="submit" name="action" value="add_domains" class="btn btn-success">✓ Add Selected to Blocklist</button>
              <button type="submit" name="action" value="ignore_domains" class="btn btn-secondary">🗑 Ignore Selected</button>
            </div>
          </form>`;

if (regex.test(code)) {
    code = code.replace(regex, newForm);
    fs.writeFileSync('c:/files/SafeBrowser/worker.js', code);
    console.log("Successfully patched form HTML");
} else {
    console.log("Regex did not match");
}
