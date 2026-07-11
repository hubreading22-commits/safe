const fs = require('fs');

let code = fs.readFileSync('c:/files/SafeBrowser/worker.js', 'utf8');

// Fix filteredPending
code = code.replace(
    /const filteredPending = pending\.filter\(d => \{\r?\n\s*const lower = d\.toLowerCase\(\);\r?\n\s*return !blockedSet\.has\(lower\) && !ignoredSet\.has\(lower\);\r?\n\s*\}\);/g,
    `const filteredPending = pending.filter(d => {
        const lower = (typeof d === 'string' ? d : (d && d.domain ? d.domain : '')).toLowerCase();
        return lower && !blockedSet.has(lower) && !ignoredSet.has(lower);
    });`
);

// Fix pendingList
code = code.replace(
    /const pendingList = filteredPending\.map\(\(domain\) => `\r?\n\s*<div class="domain-row">\r?\n\s*<label class="checkbox-label">\r?\n\s*<input type="checkbox" name="domains" value="\$\{escapeHtml\(domain\)\}" checked>\r?\n\s*<span class="domain-name">\$\{escapeHtml\(domain\)\}<\/span>\r?\n\s*<\/label>\r?\n\s*<span class="device-badge">📱<\/span>\r?\n\s*<\/div>\r?\n\s*`\)\.join\(''\);/g,
    `const pendingList = filteredPending.map((item) => {
        const dom = typeof item === 'string' ? item : (item.domain || '');
        const title = typeof item === 'string' ? '' : (item.title || '');
        const desc = typeof item === 'string' ? '' : (item.description || '');
        const titleHtml = title ? \`<div style="font-size: 12px; color: #6b7280; margin-top: 4px; font-weight: 500;">\${escapeHtml(title)}</div>\` : '';
        const descHtml = desc ? \`<div style="font-size: 11px; color: #9ca3af; margin-top: 2px; font-style: italic;">\${escapeHtml(desc)}</div>\` : '';
        return \`
    <div class="domain-row">
      <label class="checkbox-label" style="align-items: flex-start;">
        <input type="checkbox" name="domains" value="\${escapeHtml(dom)}" checked style="margin-top: 4px;">
        <div style="display: flex; flex-direction: column;">
          <span class="domain-name">\${escapeHtml(dom)}</span>
          \${titleHtml}
          \${descHtml}
        </div>
      </label>
      <span class="device-badge" title="Tracked from Android">📱</span>
    </div>\`;
    }).join('');`
);

fs.writeFileSync('c:/files/SafeBrowser/worker.js', code);
console.log("Successfully replaced pending list generator!");
