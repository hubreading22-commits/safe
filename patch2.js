const fs = require('fs');

let code = fs.readFileSync('c:/files/SafeBrowser/worker.js', 'utf8');

// 1. Update deny_unblock redirect
code = code.replace(
    /if \(actionStr === 'deny_unblock'\) \{([\s\S]*?)return redirectToAdmin\(url\.origin, 'removed', 1\);/g,
    `if (actionStr === 'deny_unblock') {$1return redirectToAdmin(url.origin, 'req_denied', 1);`
);

// 2. Add req_denied to messages
code = code.replace(
    /removed: 'Domain removed from blocklist',/g,
    `removed: 'Domain removed from blocklist',\n        req_denied: 'Request removed from unblock list',`
);

// 3. Remove 'checked' from pending items
code = code.replace(
    /checked style="margin-top: 4px;"/g,
    `style="margin-top: 4px;"`
);

// 4. Unify the Block and Ignore forms
const oldForm = `<form method="POST" action="/admin/action">
            <input type="hidden" name="action" value="add_domains">
            <div class="domain-list">\${pendingList}</div>
            <div class="action-bar"><button type="submit" class="btn btn-success">✓ Add Selected to Blocklist</button></div>
          </form>
          <form method="POST" action="/admin/action" style="margin-top:10px">
            <input type="hidden" name="action" value="ignore_domains">
            \${filteredPending.map((d) => \`<input type="hidden" name="domains" value="\${escapeHtml(d)}">\`).join('')}
            <button type="submit" class="btn btn-secondary">🗑 Ignore All</button>
          </form>`;

const newForm = `<form method="POST" action="/admin/action">
            <div class="domain-list">\${pendingList}</div>
            <div class="action-bar" style="display:flex; gap:10px;">
              <button type="submit" name="action" value="add_domains" class="btn btn-success">✓ Add Selected to Blocklist</button>
              <button type="submit" name="action" value="ignore_domains" class="btn btn-secondary">🗑 Ignore Selected</button>
            </div>
          </form>`;

code = code.replace(oldForm, newForm);

fs.writeFileSync('c:/files/SafeBrowser/worker.js', code);
console.log("Successfully patched worker.js");
