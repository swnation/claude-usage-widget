# 스킨 제작 가이드

## 개요
Android 위젯 앱의 커스텀 스킨. **플로팅 오버레이, 앱 화면, 홈 위젯, 알림** 4곳에 적용됨.
한 번 적용한 스킨은 **로컬에 자동 저장**되어 언제든 다시 불러올 수 있음.

## 앱 내 스킨 기능

| 기능 | 설명 |
|------|------|
| 기본 스킨 10종 | 탭 한 번으로 적용 |
| 📷 사진 스킨 | 갤러리 사진을 배경으로 |
| 📄 파일 스킨 | .cskin (JSON) 파일 로드 |
| ZIP 스킨 | .cskin + 배경 이미지 한 파일로 |
| 🖼 배경 추가 | 파일 스킨에 배경 이미지 별도 추가 |
| 저장된 스킨 | 이전에 적용한 스킨 목록. 탭=적용, 롱프레스=삭제 |
| 플로팅 스킨 토글 | 오버레이 스킨 ON/OFF (앱 스킨과 독립) |

---

## 배포 형식

### 방법 1: ZIP 패키지 (권장 — 배경 이미지 포함)
```
myskin.zip
├── skin.cskin          ← 색상/스타일 JSON (필수)
├── background.png      ← 앱 배경 이미지 (선택)
└── overlay_bg.png      ← 오버레이 전용 배경 (선택)
```
- **background.png**: 앱 전체 배경. centerCrop으로 비율 유지. 권장 해상도 **1080x2400** (9:20)
- **overlay_bg.png**: 오버레이에만 적용. 없으면 background.png 공유. **투명 PNG**로 커스텀 모양 가능
- 이미지 권장: **1MB 이하**
- 파일명 규칙: JSON은 `.cskin` 또는 `.json`, 오버레이 배경은 `overlay_bg` 또는 `overlay.`로 시작

### 방법 2: .cskin 단일 파일 (색상만)
```
myskin.cskin  ← JSON 파일
```
사용자가 앱에서 🖼배경 버튼으로 이미지를 나중에 추가 가능.

> **중요**: JSON 안 `backgroundImage` 필드에 base64를 직접 넣지 마세요. 고해상도 이미지는 수MB가 되어 앱이 크래시됩니다. 반드시 ZIP 패키지 방식을 사용하세요.

---

## .cskin JSON 구조

```json
{
  "name": "스킨 이름",
  "author": "제작자",
  "version": 1,
  "overlay": { ... },
  "app": { ... },
  "widget": { ... }
}
```

---

## 1. `overlay` — 플로팅 오버레이

화면 위에 떠 있는 작은 사용량 표시창. 사용자가 "플로팅 오버레이에도 스킨 적용" 체크를 끄면 기본 다크로 표시됨.

```json
"overlay": {
  "background": {
    "type": "gradient",
    "colors": ["#1a0040", "#0a0820"],
    "direction": "top_bottom",
    "opacity": 0.95
  },
  "text": {
    "color": "#00FFFF",
    "shadow": { "color": "#00FFC0", "radius": 8.0, "dx": 0.0, "dy": 0.0 }
  },
  "shape": {
    "cornerRadius": 4.0,
    "border": { "color": "#FF00FF", "width": 1.0 },
    "elevation": 10.0
  },
  "padding": { "horizontal": 20, "vertical": 16 }
}
```

### background

| 필드 | 타입 | 설명 | 기본값 |
|------|------|------|--------|
| type | string | `"gradient"` (2+색), `"solid"` (단색), `"image"` (ZIP 내 overlay_bg 사용) | `"gradient"` |
| colors | string[] | hex 색상 배열. solid=1개, gradient=2개+ | — |
| direction | string | 그라데이션 방향 (아래 표) | `"tl_br"` |
| opacity | number | 투명도 0.0~1.0 | `0.92` |

**그라데이션 방향:**
`tl_br` (좌상→우하), `top_bottom`, `left_right`, `bl_tr`, `tr_bl`, `bottom_top`, `right_left`

### text

| 필드 | 설명 | 기본값 |
|------|------|--------|
| color | 텍스트 색상 | `"#e0e0e0"` |
| shadow.color | 그림자 색상 | — |
| shadow.radius | 그림자 블러 (0이면 없음). **네온 효과: 6~12** | `0` |
| shadow.dx / dy | 그림자 오프셋 | `0` |

### shape

| 필드 | 설명 | 기본값 |
|------|------|--------|
| cornerRadius | 모서리 둥글기 (dp) | `16` |
| border.color | 테두리 색상 | — |
| border.width | 테두리 두께 (0이면 없음) | `0` |
| elevation | 그림자 높이 (dp). **투명 PNG 사용 시 0 권장** | `4` |
| **clipPath** | SVG path data로 커스텀 모양 (다이아몬드, 별 등) | 없음 |
| **width** | clipPath 기준 너비 (path 좌표계) | 자동 |
| **height** | clipPath 기준 높이 (path 좌표계) | 자동 |

### padding

| 필드 | 설명 | 기본값 |
|------|------|--------|
| horizontal | 좌우 여백 (px) | `24` |
| vertical | 상하 여백 (px) | `14` |

---

## 2. `app` — 앱 메인 화면

```json
"app": {
  "backgroundColor": "#0D0804",
  "sectionColor": "#1A1108",
  "sectionOpacity": 0.75,
  "cardColor": "#2A1B12",
  "textColor": "#D4B895",
  "subtextColor": "#8C7355",
  "accentColor": "#8B0000",
  "isDark": true
}
```

| 필드 | 설명 | 기본값 |
|------|------|--------|
| backgroundColor | 메인 배경색 (배경 이미지 없을 때) | `"#1a1a2e"` |
| sectionColor | 접이식 섹션 배경색 | `"#16213e"` |
| **sectionOpacity** | 섹션 투명도. **배경 이미지 사용 시 0.6~0.8 권장** | `1.0` |
| cardColor | 카드/입력 필드 배경색 | `"#22223a"` |
| textColor | 주요 텍스트 | `"#e0e0e0"` |
| subtextColor | 보조 텍스트 | `"#888899"` |
| **accentColor** | **강조색** — 버튼, 섹션 제목, 라디오 등 | `"#c084fc"` |
| isDark | true: 밝은 텍스트 / false: 어두운 텍스트 | `true` |

> ZIP에 `background.png`가 있으면 **centerCrop**으로 표시 (비율 유지, 화면에 꽉 참). 어떤 비율의 이미지든 깨지지 않음.

---

## 3. `widget` — 홈 화면 위젯

```json
"widget": {
  "backgroundColor": "#1A1108",
  "opacity": 0.95
}
```

| 필드 | 설명 | 기본값 |
|------|------|--------|
| backgroundColor | 위젯 배경색 | `"#1a1a2e"` |
| opacity | 위젯 투명도 | `0.87` |

---

## 오버레이 커스텀 모양

오버레이를 **원, 다이아몬드, 별, 캐릭터 실루엣** 등 자유로운 모양으로 만들 수 있습니다.

### 방법 A: SVG clipPath (기하학적 모양)

```json
"shape": {
  "clipPath": "M50,0 L100,50 L50,100 L0,50 Z",
  "width": 100,
  "height": 100,
  "cornerRadius": 0,
  "elevation": 8
}
```

**clipPath 예시:**

| 모양 | clipPath | width | height |
|------|----------|-------|--------|
| 다이아몬드 | `M50,0 L100,50 L50,100 L0,50 Z` | 100 | 100 |
| 원형 | `M50,0 A50,50 0 1,1 50,100 A50,50 0 1,1 50,0 Z` | 100 | 100 |
| 별 | `M50,0 L61,35 L98,35 L68,57 L79,91 L50,70 L21,91 L32,57 L2,35 L39,35 Z` | 100 | 91 |

- `width`/`height`: path 좌표계 기준. 실제 오버레이 크기에 맞춰 자동 스케일링
- Android는 **볼록(convex) 도형만** 그림자(elevation) 지원. 오목한 모양은 그림자가 사각형으로 폴백

### 방법 B: 투명 PNG (캐릭터, 복잡한 모양)

1. 원하는 모양의 PNG를 **투명 배경**으로 제작
2. 가운데에 텍스트가 들어갈 공간 확보
3. `overlay_bg.png`로 ZIP에 포함
4. `.cskin`에서 `"type": "image"`, `"elevation": 0` 설정

**권장 사이즈:**
- **300x120px** ~ **400x160px** (가로형)
- **200x200px** (정사각/원형)
- **160x200px** (세로형, 캐릭터)

```json
"overlay": {
  "background": { "type": "image", "opacity": 1.0 },
  "text": {
    "color": "#FFFFFF",
    "shadow": { "color": "#000000", "radius": 3, "dx": 1, "dy": 1 }
  },
  "shape": { "cornerRadius": 0, "elevation": 0 },
  "padding": { "horizontal": 30, "vertical": 20 }
}
```

### 방법 C: clipPath + 투명 PNG 조합 (최상 퀄리티)

clipPath로 모양을 자르고, 투명 PNG로 텍스처를 입히면 가장 정교한 결과물.

---

## 주의사항

1. **색상**: `#` 포함 6자리 hex (`#FF0000`). 8자리 ARGB도 가능 (`#80FF0000`)
2. **solid**: `colors`에 1개
3. **gradient**: `colors`에 2개 이상
4. **배경 이미지**: 반드시 ZIP으로. base64 직접 삽입 금지 (OOM 크래시)
5. **sectionOpacity**: 배경 이미지 사용 시 `0.6~0.8` 권장
6. **오버레이 배경 분리**: `overlay_bg.png` → 오버레이에만, `background.png` → 앱 배경
7. **앱 배경 해상도**: **1080x2400** (9:20) 권장. centerCrop이라 어떤 비율이든 깨지지 않음
8. **투명 PNG 오버레이**: `elevation: 0`으로 설정해야 사각 그림자가 안 보임
9. **스킨 자동 저장**: 한 번 적용한 스킨은 로컬에 저장되어 "저장된 스킨"에서 바로 재적용 가능

---

## 예시 1: 네온 사이버펑크 (.cskin 단일)

```json
{
  "name": "네온 사이버펑크",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": {
      "type": "gradient",
      "colors": ["#0a0820", "#1a0040", "#0a0820"],
      "direction": "top_bottom",
      "opacity": 0.95
    },
    "text": {
      "color": "#4DDFFC",
      "shadow": { "color": "#00FFC0", "radius": 8.0, "dx": 0.0, "dy": 0.0 }
    },
    "shape": {
      "cornerRadius": 4.0,
      "border": { "color": "#FF00FF", "width": 1.0 },
      "elevation": 10.0
    }
  },
  "app": {
    "backgroundColor": "#0a0820",
    "sectionColor": "#150030",
    "cardColor": "#1a0040",
    "textColor": "#4DDFFC",
    "subtextColor": "#A0B0D0",
    "accentColor": "#FF00FF",
    "isDark": true
  },
  "widget": { "backgroundColor": "#0a0820", "opacity": 0.9 }
}
```

## 예시 2: 디아블로 II (ZIP 패키지)

**ZIP 내용물:**
- `skin.cskin` — 아래 JSON
- `background.png` — 어두운 석조/불꽃 배경 (1080x2400)
- `overlay_bg.png` — 돌 프레임 텍스처 (300x120, 투명 배경)

```json
{
  "name": "디아블로 II",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": { "type": "image", "opacity": 0.95 },
    "text": {
      "color": "#E2D6C8",
      "shadow": { "color": "#000000", "radius": 4.0, "dx": 2.0, "dy": 2.0 }
    },
    "shape": { "cornerRadius": 2.0, "border": { "color": "#4A3B2B", "width": 2.0 }, "elevation": 0 }
  },
  "app": {
    "backgroundColor": "#0D0804",
    "sectionColor": "#1A1108",
    "sectionOpacity": 0.75,
    "cardColor": "#2A1B12",
    "textColor": "#D4B895",
    "subtextColor": "#8C7355",
    "accentColor": "#8B0000",
    "isDark": true
  },
  "widget": { "backgroundColor": "#1A1108", "opacity": 0.95 }
}
```

## 예시 3: 몬치치 (ZIP + 밝은 테마)

**ZIP 내용물:**
- `skin.cskin` — 아래 JSON
- `background.jpg` — 몬치치 캐릭터 배경 (1080x2400)
- `overlay_bg.png` — 갈색 털 질감 (300x120, 투명 배경)

```json
{
  "name": "몬치치",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": { "type": "image", "opacity": 0.95 },
    "text": {
      "color": "#FFB6C1",
      "shadow": { "color": "#5C2E0E", "radius": 2.0, "dx": 1.0, "dy": 1.0 }
    },
    "shape": { "cornerRadius": 16.0, "border": { "color": "#D2B48C", "width": 3.0 }, "elevation": 0 }
  },
  "app": {
    "backgroundColor": "#FDF5E6",
    "sectionColor": "#FFF8DC",
    "sectionOpacity": 0.8,
    "cardColor": "#FAEBD7",
    "textColor": "#3E1F09",
    "subtextColor": "#A0522D",
    "accentColor": "#D87093",
    "isDark": false
  },
  "widget": { "backgroundColor": "#8B4513", "opacity": 0.9 }
}
```

## 예시 4: 별 모양 오버레이 (clipPath)

```json
{
  "name": "별",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": { "type": "solid", "colors": ["#FFD700"], "opacity": 0.95 },
    "text": { "color": "#000000" },
    "shape": {
      "clipPath": "M50,0 L61,35 L98,35 L68,57 L79,91 L50,70 L21,91 L32,57 L2,35 L39,35 Z",
      "width": 100,
      "height": 91,
      "elevation": 0
    },
    "padding": { "horizontal": 35, "vertical": 30 }
  },
  "app": {
    "backgroundColor": "#1a1a2e",
    "sectionColor": "#16213e",
    "cardColor": "#22223a",
    "textColor": "#e0e0e0",
    "subtextColor": "#888899",
    "accentColor": "#FFD700",
    "isDark": true
  },
  "widget": { "backgroundColor": "#1a1a2e", "opacity": 0.87 }
}
```

---

## 디자인 팁

| 테마 | 설정 |
|------|------|
| 다크 | `isDark: true`, 배경 어둡게, 텍스트 밝게 |
| 라이트 | `isDark: false`, 배경 밝게, 텍스트 어둡게 |
| 네온 | `text.shadow.radius: 6~12`, 형광색, 어두운 배경 |
| 미니멀 | `cornerRadius` 작게, `border` 없음, `elevation` 낮게 |
| 고급 | ZIP에 고퀄 배경 + `sectionOpacity: 0.7` |
| 캐릭터 오버레이 | 투명 PNG + `elevation: 0` |
| 기하학 모양 | `clipPath`로 다이아몬드/별/원형 |

## 스킨 제작 체크리스트

- [ ] `name`, `author` 설정
- [ ] `overlay` 배경 타입 선택 (gradient / solid / image)
- [ ] `overlay` 텍스트 색상 + 가독성 확인
- [ ] `app` 배경색/강조색/텍스트 색상 설정
- [ ] `app.isDark` 올바르게 설정
- [ ] `widget` 배경색 설정
- [ ] 배경 이미지: ZIP으로 패키징 (base64 금지)
- [ ] 오버레이 전용 배경 필요시: `overlay_bg.png` 별도 제작
- [ ] 배경 이미지 사용 시: `sectionOpacity` 0.6~0.8
- [ ] 투명 PNG 사용 시: `elevation: 0`
