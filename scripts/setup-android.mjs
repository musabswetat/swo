import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const root = process.cwd();
const android = path.join(root, 'android');
if (!fs.existsSync(android)) {
  execSync('npx cap add android', { stdio: 'inherit', cwd: root });
}

const javaRoot = path.join(android, 'app/src/main/java/com/example/shorts/plugins');
fs.mkdirSync(javaRoot, { recursive: true });
fs.copyFileSync(
  path.join(root, 'native-plugin/src/main/java/com/example/shorts/plugins/ShortsYtDlpPlugin.java'),
  path.join(javaRoot, 'ShortsYtDlpPlugin.java')
);

// Android app module dependency.
const gradle = path.join(android, 'app/build.gradle');
let g = fs.readFileSync(gradle, 'utf8');
if (!g.includes('yt-dlp-android:2.0.2')) {
  g = g.replace(/dependencies\s*\{/, `dependencies {\n    implementation 'dev.ffmpegkit-maintained:yt-dlp-android:2.0.2'`);
  fs.writeFileSync(gradle, g);
}

// Internet permission.
const manifest = path.join(android, 'app/src/main/AndroidManifest.xml');
let m = fs.readFileSync(manifest, 'utf8');
if (!m.includes('android.permission.INTERNET')) {
  m = m.replace(/(<manifest[^>]*>)/, '$1\n    <uses-permission android:name="android.permission.INTERNET" />');
  fs.writeFileSync(manifest, m);
}

// Register plugin in the generated MainActivity.java.
const mainDir = path.join(android, 'app/src/main/java/com/example/shorts');
const candidates = ['MainActivity.java', 'MainActivity.kt'];
let mainFile = candidates.map(x => path.join(mainDir, x)).find(fs.existsSync);
if (!mainFile) throw new Error('MainActivity not found at ' + mainDir);
let a = fs.readFileSync(mainFile, 'utf8');
if (mainFile.endsWith('.java')) {
  if (!a.includes('ShortsYtDlpPlugin')) {
    a = a.replace('import com.getcapacitor.BridgeActivity;', 'import com.getcapacitor.BridgeActivity;\nimport com.example.shorts.plugins.ShortsYtDlpPlugin;');
    a = `package com.example.shorts;\n\nimport android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;\nimport com.example.shorts.plugins.ShortsYtDlpPlugin;\n\npublic class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        super.onCreate(savedInstanceState);\n        registerPlugin(ShortsYtDlpPlugin.class);\n    }\n}\n`;
  }
} else {
  // Fallback for a Kotlin template.
  if (!a.includes('ShortsYtDlpPlugin')) {
    a = `package com.example.shorts\n\nimport com.getcapacitor.BridgeActivity\nimport com.example.shorts.plugins.ShortsYtDlpPlugin\n\nclass MainActivity : BridgeActivity() {\n    override fun onCreate(savedInstanceState: android.os.Bundle?) {\n        super.onCreate(savedInstanceState)\n        registerPlugin(ShortsYtDlpPlugin::class.java)\n    }\n}\n`;
  }
}
fs.writeFileSync(mainFile, a);
console.log('Android project prepared with native yt-dlp plugin.');
