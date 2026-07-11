const fs = require('fs');

let code = fs.readFileSync('c:/files/SafeBrowser/worker.js', 'utf8');

// Find the end of the unblockReqs div (line 970 area)
const index = code.indexOf(`        ignored: count + ' domain(s) ignored',`);
if (index === -1) {
    console.log("Could not find the broken section");
    process.exit(1);
}

// Keep everything before the broken part
const newCode = code.substring(0, index) + `      <!-- Quick Info -->
      <div class="card">
        <h2>📊 Quick Info</h2>
        <div style="font-size: 14px; color: #6b7280; line-height: 2;">
          <div><strong>API:</strong> <code style="background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:12px;">POST /api/domains</code></div>
          <div><strong>Config:</strong> <code style="background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:12px;">GET /api/config</code></div>
          <div><strong>Debug:</strong> <code style="background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:12px;">GET /debug</code></div>
          <div style="margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0f0f0;">
            <strong>Android URLs:</strong>
            <div style="margin-top: 8px; background: #f8fafc; padding: 12px; border-radius: 8px; font-family: monospace; font-size: 12px; line-height: 1.8;">
              UPLOAD_URL = "https://browser.proxybotkk.workers.dev/api/domains"<br>
              REMOTE_CONFIG_URL = "https://browser.proxybotkk.workers.dev/api/config"
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div class="footer">SafeBrowser Admin Dashboard • Built with Cloudflare Workers + KV</div>

  <!-- Full Data Modal -->
  <div id="dataModal" class="modal-overlay">
    <div class="modal-content">
      <div class="modal-header">
        <h3>Full Data View</h3>
        <button class="modal-close" onclick="closeDataModal()">✕</button>
      </div>
      <div class="modal-body">
        <div class="field">
          <div class="label">Domain / URL</div>
          <div id="modalDom" class="value"></div>
        </div>
        <div class="field">
          <div class="label">Page Title</div>
          <div id="modalTitle" class="value"></div>
        </div>
        <div class="field">
          <div class="label">Meta Description</div>
          <div id="modalDesc" class="value"></div>
        </div>
      </div>
    </div>
  </div>

  <script>
    function openDataModal(dom, title, desc) {
      document.getElementById('modalDom').textContent = dom || 'N/A';
      document.getElementById('modalTitle').textContent = title || 'No Title Available';
      document.getElementById('modalDesc').textContent = desc || 'No Description Available';
      document.getElementById('dataModal').classList.add('active');
    }
    function closeDataModal() {
      document.getElementById('dataModal').classList.remove('active');
    }

    const params = new URLSearchParams(window.location.search);
    const msg = params.get('msg');
    const count = params.get('count');
    if (msg) {
      const messages = {
        added: count + ' domain(s) added to blocklist',
        ignored: count + ' domain(s) ignored',
        removed: 'Domain removed from blocklist',
        req_denied: 'Request removed from unblock list',
        unignored: 'Domain un-ignored and will show in pending again',
        updated: 'Settings updated successfully'
      };
      const toast = document.createElement('div');
      toast.className = 'toast';
      toast.textContent = messages[msg] || 'Action completed';
      document.body.appendChild(toast);
      setTimeout(() => toast.remove(), 3000);
      window.history.replaceState({}, '', '/admin');
    }
  </script>
</body>
</html>\`;
}
`;

fs.writeFileSync('c:/files/SafeBrowser/worker.js', newCode);
console.log("Successfully restored worker.js");
