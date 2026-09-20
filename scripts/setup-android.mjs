import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const root = process.cwd();
const android = path.join(root, 'android');
if (!fs.existsSync(android)) {
  execSync('npx cap add android', { stdio: 'inherit', cwd: root });
}

// 1. نسخ كود إضافة yt-dlp الأصلية
const javaRoot = path.join(android, 'app/src/main/java/com/example/shorts/plugins');
fs.mkdirSync(javaRoot, { recursive: true });
fs.copyFileSync(
  path.join(root, 'native-plugin/src/main/java/com/example/shorts/plugins/ShortsYtDlpPlugin.java'),
  path.join(javaRoot, 'ShortsYtDlpPlugin.java')
);

// 2. إضافة مستودع JitPack إلى build.gradle الرئيسي
const rootGradle = path.join(android, 'build.gradle');
if (fs.existsSync(rootGradle)) {
  let rg = fs.readFileSync(rootGradle, 'utf8');
  if (!rg.includes('https://jitpack.io')) {
    rg = rg.replace(/allprojects\s*\{\s*repositories\s*\{/, `allprojects {\n    repositories {\n        maven { url 'https://jitpack.io' }`);
    fs.writeFileSync(rootGradle, rg);
  }
}

// 3. إضافة التبعيات الرسمية و abiFilters في app/build.gradle
const appGradle = path.join(android, 'app/build.gradle');
let g = fs.readFileSync(appGradle, 'utf8');

// إضافة دعم المعماريات المدعومة لـ Python/FFmpeg
if (!g.includes('ndk {')) {
  g = g.replace(/defaultConfig\s*\{/, `defaultConfig {\n        ndk {\n            abiFilters 'arm64-v8a', 'x86_64'\n        }`);
}

// إضافة الحزم الحقيقية لـ youtubedl-android
if (!g.includes('youtubedl-android')) {
  g = g.replace(/dependencies\s*\{/, `dependencies {\n    implementation 'com.github.yausername.youtubedl-android:library:0.17.4'\n    implementation 'com.github.yausername.youtubedl-android:ffmpeg:0.17.4'`);
  fs.writeFileSync(appGradle, g);
}

// 4. ضبط أذونات الإنترنت في AndroidManifest
const manifest = path.join(android, 'app/src/main/AndroidManifest.xml');
let m = fs.readFileSync(manifest, 'utf8');
if (!m.includes('android.permission.INTERNET')) {
  m = m.replace(/(<manifest[^>]*>)/, '$1\n    <uses-permission android:name="android.permission.INTERNET" />');
  fs.writeFileSync(manifest, m);
}

// 5. تسجيل الإضافة في MainActivity
const mainDir = path.join(android, 'app/src/main/java/com/example/shorts');
const candidates = ['MainActivity.java', 'MainActivity.kt'];
let mainFile = candidates.map(x => path.join(mainDir, x)).find(fs.existsSync);
if (!mainFile) throw new Error('MainActivity not found at ' + mainDir);

let a = fs.readFileSync(mainFile, 'utf8');
if (mainFile.endsWith('.java')) {
  if (!a.includes('ShortsYtDlpPlugin')) {
    a = `package com.example.shorts;\n\nimport android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;\nimport com.example.shorts.plugins.ShortsYtDlpPlugin;\n\npublic class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        registerPlugin(ShortsYtDlpPlugin.class);\n        super.onCreate(savedInstanceState);\n    }\n}\n`;
  }
} else {
  if (!a.includes('ShortsYtDlpPlugin')) {
    a = `package com.example.shorts\n\nimport android.os.Bundle\nimport com.getcapacitor.BridgeActivity\nimport com.example.shorts.plugins.ShortsYtDlpPlugin\n\nclass MainActivity : BridgeActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        registerPlugin(ShortsYtDlpPlugin::class.java)\n        super.onCreate(savedInstanceState)\n    }\n}\n`;
  }
}
fs.writeFileSync(mainFile, a);
console.log('Android project prepared successfully with Native yt-dlp dependencies.');
