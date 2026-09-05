import zipfile

# Check source code
lines = open('api/server-shared/src/main/java/rikka/shizuku/server/Service.java', encoding='utf-8').readlines()
for i, line in enumerate(lines):
    if 'HIDE_DEBUG' in line or 'HIDE_INTERCEPT' in line or 'targetCode == 90' in line:
        print(f'{i+1}: {line.rstrip()}')

# Check APK
print("\n=== APK check ===")
z = zipfile.ZipFile('manager/build/outputs/apk/dropin/release/manager-dropin-release.apk')
data = z.read('classes.dex')
for kw in ['HIDE_DEBUG', 'HIDE_INTERCEPT']:
    idx = data.find(kw.encode())
    status = f"FOUND at {idx}" if idx != -1 else "NOT FOUND"
    print(f'{kw}: {status}')
