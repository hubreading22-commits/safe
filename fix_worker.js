const fs = require('fs');
let code = fs.readFileSync('worker.js', 'utf8');

// The messed up part starts at line 40:
//         // 1) POST /api/domains
//         // ═══════════════════════════════════════════════════════════
//         if (url.pathname === '/api/domains' && method === 'POST') {
//         if (url.pathname === '/admin' && method === 'GET') {
//             try {

// We will find "// 1) POST /api/domains" and replace everything until "if (url.pathname === '/admin' && method === 'GET') {"

const startTag = "// 1) POST /api/domains";
const endTag = "if (url.pathname === '/admin' && method === 'GET') {";

const startIndex = code.indexOf(startTag);
const endIndex = code.indexOf(endTag, startIndex);

if (startIndex !== -1 && endIndex !== -1) {
    const replacement = `// 1) POST /api/domains
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/domains' && method === 'POST') {
            try {
                const body = await request.json();
                const incoming = body.domains || [];
                if (!Array.isArray(incoming) || incoming.length === 0) {
                    return jsonResponse({ ok: false, error: 'No domains provided' }, 400, corsHeaders);
                }
                const normalized = incoming
                    .map(d => String(d).toLowerCase().trim().replace(/^www\\./, ''))
                    .filter(d => d.length > 0);
                const pendingRaw = await kvGet('pending_domains', '[]');
                const pending = JSON.parse(pendingRaw);
                const merged = [...new Set([...pending, ...normalized])];
                await kvPut('pending_domains', JSON.stringify(merged));
                return jsonResponse({ ok: true, added: normalized.length, total_pending: merged.length }, 200, corsHeaders);
            } catch (e) {
                return jsonResponse({ ok: false, error: e.message }, 500, corsHeaders);
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 1.5) POST /api/unblock
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/unblock' && method === 'POST') {
            try {
                const body = await request.json();
                if (!body.domain) return jsonResponse({ ok: false }, 400, corsHeaders);
                const domain = String(body.domain).toLowerCase().trim().replace(/^www\\./, '');
                
                const requestsRaw = await kvGet('unblock_requests', '[]');
                const requests = JSON.parse(requestsRaw);
                const merged = [...new Set([...requests, domain])];
                await kvPut('unblock_requests', JSON.stringify(merged));
                return jsonResponse({ ok: true }, 200, corsHeaders);
            } catch (e) {
                return jsonResponse({ ok: false, error: e.message }, 500, corsHeaders);
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 2) GET /api/config
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/config' && method === 'GET') {
            const configRaw = await kvGet('config');
            let config = {
                version: '1.0.0',
                updated: new Date().toISOString(),
                blocked_domains: [],
                blocked_keywords: [],
                video_blocking: true,
                audio_blocking: true,
                shortcuts: []
            };
            if (configRaw) {
                try {
                    const parsed = JSON.parse(configRaw);
                    config = { ...config, ...parsed };
                } catch(e) {}
            }
            return new Response(JSON.stringify(config), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // ═══════════════════════════════════════════════════════════
        // 3) GET /admin
        // ═══════════════════════════════════════════════════════════
        `;

    code = code.substring(0, startIndex) + replacement + code.substring(endIndex);
    fs.writeFileSync('worker.js', code);
    console.log("worker.js fixed successfully!");
} else {
    console.log("Tags not found!");
}
