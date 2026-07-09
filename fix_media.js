const fs = require('fs');
let code = fs.readFileSync('app/src/main/java/com/yourcompany/safebrowser/MainActivity.kt', 'utf8');

const targetMethod = `    private fun getMediaBlockScript(): String {`;
const endMarker = `        """.trimIndent()
    }`;

const startIndex = code.indexOf(targetMethod);
if (startIndex === -1) {
    console.error("Method not found");
    process.exit(1);
}
const endIndex = code.indexOf(endMarker, startIndex) + endMarker.length;

const replacement = `    private fun getMediaBlockScript(): String {
        return """
            (function() {
                function isVideoBlocked() { return window.AndroidTheme ? window.AndroidTheme.isVideoBlocked() : false; }
                function isAudioBlocked() { return window.AndroidTheme ? window.AndroidTheme.isAudioBlocked() : false; }
                
                function neuter(el) {
                    try { if (el.pause) el.pause(); } catch(e) {}
                    try { el.src = ""; el.removeAttribute("src"); if (el.load) el.load(); } catch(e) {}
                    try { el.remove(); } catch(e) {}
                }

                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        var isVid = this instanceof HTMLVideoElement;
                        var isAud = this instanceof HTMLAudioElement;
                        if ((isVid && isVideoBlocked()) || (isAud && isAudioBlocked())) {
                            neuter(this);
                            return Promise.reject(new DOMException("Media blocked", "NotAllowedError"));
                        }
                        return origPlay.apply(this, arguments);
                    };
                } catch(e) {}

                try {
                    var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "src");
                    if (srcDesc && srcDesc.configurable) {
                        var origSet = srcDesc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, "src", {
                            get: function() { return ""; },
                            set: function(v) {
                                var isVid = this instanceof HTMLVideoElement;
                                var isAud = this instanceof HTMLAudioElement;
                                if ((isVid && isVideoBlocked()) || (isAud && isAudioBlocked())) return;
                                if (origSet) origSet.call(this, v);
                            },
                            configurable: true
                        });
                    }
                } catch(e) {}

                try {
                    var srcObjDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "srcObject");
                    if (srcObjDesc && srcObjDesc.configurable) {
                        var origObjSet = srcObjDesc.set;
                        Object.defineProperty(HTMLMediaElement.prototype, "srcObject", {
                            get: function() { return null; },
                            set: function(v) {
                                var isVid = this instanceof HTMLVideoElement;
                                var isAud = this instanceof HTMLAudioElement;
                                if ((isVid && isVideoBlocked()) || (isAud && isAudioBlocked())) return;
                                if (origObjSet) origObjSet.call(this, v);
                            },
                            configurable: true
                        });
                    }
                } catch(e) {}

                try {
                    var origCreateElement = document.createElement.bind(document);
                    document.createElement = function(tagName) {
                        var el = origCreateElement(tagName);
                        if (typeof tagName === "string") {
                            if ((tagName.toLowerCase() === "video" && isVideoBlocked()) || (tagName.toLowerCase() === "audio" && isAudioBlocked())) {
                                neuter(el);
                            }
                        }
                        return el;
                    };
                } catch(e) {}
                
                try {
                    var OrigAudioContext = window.AudioContext;
                    window.AudioContext = function() { 
                        if (isAudioBlocked()) throw new Error("Audio blocked"); 
                        return new OrigAudioContext();
                    };
                } catch(e) {}
                
                try {
                    var OrigWebkitAudioContext = window.webkitAudioContext;
                    window.webkitAudioContext = function() { 
                        if (isAudioBlocked()) throw new Error("Audio blocked"); 
                        return new OrigWebkitAudioContext();
                    };
                } catch(e) {}

                try {
                    var OrigAudio = window.Audio;
                    window.Audio = function() { 
                        if (isAudioBlocked()) {
                            var fakeAudio = document.createElement("audio");
                            neuter(fakeAudio);
                            return fakeAudio; 
                        }
                        return new OrigAudio();
                    };
                } catch(e) {}

                var blockedIframeHosts = ["youtube", "youtu.be", "vimeo", "dailymotion", "twitch", "tiktok", "netflix", "primevideo", "disney", "hulu", "spotify", "soundcloud", "mixcloud"];
                function killMedia() {
                    if (isVideoBlocked()) document.querySelectorAll("video").forEach(neuter);
                    if (isAudioBlocked()) document.querySelectorAll("audio").forEach(neuter);
                    if (isVideoBlocked() || isAudioBlocked()) {
                        document.querySelectorAll("iframe").forEach(function(f) {
                            var src = (f.src || "").toLowerCase();
                            if (blockedIframeHosts.some(function(h) { return src.includes(h); })) {
                                f.src = "about:blank"; f.remove();
                            }
                        });
                    }
                }

                function startObserving() {
                    var observer = new MutationObserver(killMedia);
                    observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
                }
                
                if (document.readyState === "loading") {
                    document.addEventListener("DOMContentLoaded", function() { killMedia(); startObserving(); });
                } else {
                    killMedia(); startObserving();
                }
            })();
        """.trimIndent()
    }`;

code = code.substring(0, startIndex) + replacement + code.substring(endIndex);
fs.writeFileSync('app/src/main/java/com/yourcompany/safebrowser/MainActivity.kt', code);
console.log("Success");
