import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
pairs = [
    ('''| `evidence/ExifReader.java` | Minimal, bounded pure-Java JPEG EXIF reader: Make, Model, Software, DateTimeOriginal, OffsetTimeOriginal, and GPS position, date and time |''',
     '''| `evidence/ExifReader.java` | The one EXIF reader for JPEG, PNG and HEIF/HEIC evidence: Make, Model, Software, DateTimeOriginal, OffsetTimeOriginal, and GPS position, date and time. JPEG uses a minimal, bounded pure-Java parser. PNG (`eXIf` chunk) and HEIF/HEIC (`Exif` item) are parsed by metadata-extractor (M-132), up to 64 MiB, and their EXIF directories are interpreted by the same code. `ExifReaderFormatsTest` (written, not run) builds all three formats byte by byte |'''),
    ('''None new. The module uses `jackson-databind` and `jackson-datatype-jsr310` (Apache-2.0, the parent's Jackson BOM line,
already used by sangam-rest), `operaton-engine` (Apache-2.0, optional), and `spring-webmvc` (Apache-2.0, optional,
from the parent Spring Boot BOM). `jakarta.servlet-api` has provided scope. EXIF parsing, geohash and geometry use
no library.''',
     '''- `com.drewnoakes:metadata-extractor:2.22.0` (Apache-2.0) with its dependency `com.adobe.xmp:xmpcore:6.1.11`
  (BSD-3-Clause), for PNG and HEIF/HEIC EXIF (M-132, owner decisions 56 and 60). Both are in `licence-policy.json`
  `allowed`. The version is pinned in this module's pom until the root pom `dependencyManagement` entry lands.
- The module also uses `jackson-databind` and `jackson-datatype-jsr310` (Apache-2.0, the parent's Jackson BOM line,
  already used by sangam-rest), `operaton-engine` (Apache-2.0, optional), and `spring-webmvc` (Apache-2.0, optional,
  from the parent Spring Boot BOM). `jakarta.servlet-api` has provided scope. JPEG EXIF, geohash and geometry use no
  library.'''),
    ('''- EXIF is read only from JPEG. HEIC and PNG evidence gets hash, duplicate and fix checks but no EXIF checks.
''', '''- EXIF is read from JPEG, PNG and HEIF/HEIC. Other formats (WebP, GIF) get hash, duplicate and fix checks but no EXIF
  checks. HEIF EXIF is found through the item location box; a file with an unusual layout yields no EXIF, which the template rule `exifRequired` then flags.
'''),
]
for old, new in pairs:
    assert s.count(old) == 1, old[:60]
    s = s.replace(old, new)
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')

