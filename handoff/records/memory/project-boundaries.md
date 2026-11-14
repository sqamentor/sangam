---
name: project-boundaries
description: "Owner का नियम — कौन-से projects आपस में जुड़े हैं (Sangam, IFMS, DOIT परियोजना निगरानी, PHED) और कौन पूरी तरह अलग हैं (VyaparTrust, CVR_Automation_Claude); file/instruction/code कभी mix नहीं"
metadata:
  node_type: memory
  type: user
  originSessionId: 898f1ff7-0dcc-4d95-b425-830a9a5ead44
  modified: 2026-11-11T05:19:16.311Z
---

Owner का नियम (2026-11-08, हिंदी में लिखने का निर्देश):

**एक context (आपस में जुड़े, एक-दूसरे के काम आ सकते हैं):**
- Sangam (workflow platform, यही project — `D:\GITHUB  Project\Custom WorkFlow`)
- IFMS (Sangam का consumer; IFMS master / Trial agent sessions)
- DOIT परियोजना निगरानी प्रणाली (DOIT Project Alert / project monitoring)
- PHED

इन चारों का अपना-अपना project है; ये एक-दूसरे के काम आ सकते हैं (जैसे IFMS, Sangam को test करता है; heavy lock साझा है)। इसके अलावा कोई जुड़ाव नहीं।

**पूरी तरह अलग projects (किसी से कोई लेना-देना नहीं):**
- VyaparTrust — इसमें कुछ mix नहीं करना, न इससे कुछ लेना।
- CVR_Automation_Claude (`D:\GitHub\CVR_Automation_Claude`) — किसी से कोई लेना-देना नहीं; इस पर अभी कोई काम नहीं करना।

**Owner की अनुमति से अपवाद (2026-11-11):** VyaparTrust से सिर्फ़ ये keys या settings ली जा सकती हैं:
- AWS SSM Parameter Store की keys;
- GitHub की keys;
- security scanner की keys।

इनके अलावा VyaparTrust की कोई भी setting, code या instruction इस्तेमाल नहीं होगी। IFMS की AWS SSM parameterization भी इस्तेमाल की जा सकती है, और keys AWS के ज़रिए IFMS और Sangam दोनों के बीच साझा रहें।

इसका तरीका:
- Secret की value runtime पर सीधे process में जाएगी (SSM → env), और log या chat में कभी नहीं आएगी।
- Agent कोई secret किसी field में खुद नहीं डालेगा। GitHub secret set करना owner का काम है, या OIDC से runtime fetch।

**हर हाल में:** projects के बीच न files mix हों, न instructions, न code; कोई project किसी दूसरे में interfere न करे। जो जुड़ाव owner ने बताया है, सिर्फ़ वही रखना।

**Why:** owner चाहता है कि हर system अलग और साफ़ manage हो। 2026-11-08 को CVR के governance tool का ledger टूटने से Sangam और IFMS के push रुक गए थे, क्योंकि global git `core.hooksPath` CVR के hooks की तरफ़ है।

**How to apply:** किसी दूसरे project की file, hook, ledger, skill या instruction को Sangam में न लाना, न Sangam से वहाँ ले जाना। Sangam repo अब अपना repository-local hook इस्तेमाल करता है (`git config --local core.hooksPath .githooks`, owner की मंज़ूरी 2026-11-08); push पर CVR का approval gate नहीं लगता — push से पहले owner की मंज़ूरी फिर भी conversation में लेनी है। VyaparTrust/CVR sessions से सिर्फ़ ज़रूरी सूचना, कोई काम-साझेदारी नहीं। Related: [[project-sangam-workflow-engine]], [[feedback-shared-heavy-lock]].
