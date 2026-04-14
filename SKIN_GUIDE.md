# 스킨 제작 가이드

## 개요
Android 위젯 앱의 커스텀 스킨. **플로팅 오버레이, 앱 화면, 홈 위젯, 알림** 4곳에 적용됨.

## 배포 형식

### 방법 1: ZIP 패키지 (권장)
```
myskin.zip
├── skin.cskin          ← 색상/스타일 JSON (필수)
├── background.png      ← 앱 배경 이미지 (선택)
└── overlay_bg.png      ← 오버레이 전용 배경 (선택)
```
- **background.png**: 앱 전체 배경. centerCrop으로 표시되어 비율 깨짐 없음. 권장 해상도 **1080x2400** (9:20)
- **overlay_bg.png**: 오버레이에만 적용되는 배경. 없으면 background.png 공유. **투명 PNG**를 사용하면 커스텀 모양(곰돌이, 별 등) 가능
- 이미지 권장: **1MB 이하**

### 방법 2: .cskin 단일 파일 (색상만)
```
myskin.cskin  ← JSON 파일
```
사용자가 앱에서 🖼배경 버튼으로 이미지를 나중에 추가 가능.

> **중요**: `backgroundImage`에 base64를 직접 넣지 마세요. 앱이 크래시됩니다. 반드시 ZIP 패키지 방식을 사용하세요.

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

화면 위에 떠 있는 작은 사용량 표시창.

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
    "elevation": 10.0,
    "clipPath": "M10,0 L20,10 L10,20 L0,10 Z",
    "width": 20,
    "height": 20
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
| shadow.radius | 그림자 블러 (0이면 없음) | `0` |
| shadow.dx / dy | 그림자 오프셋 | `0` |

### shape

| 필드 | 설명 | 기본값 |
|------|------|--------|
| cornerRadius | 모서리 둥글기 (dp) | `16` |
| border.color | 테두리 색상 | — |
| border.width | 테두리 두께 (0이면 없음) | `0` |
| elevation | 그림자 높이 (dp) | `4` |
| **clipPath** | **SVG path data로 커스텀 모양** (다이아몬드, 별, 곰돌이 등) | 없음 |
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

> ZIP에 `background.png`가 있으면 배경 이미지가 **centerCrop**으로 표시됨 (비율 유지, 화면에 꽉 참)

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

## 커스텀 모양 (clipPath)

SVG path data로 오버레이를 **원, 다이아몬드, 별, 캐릭터 실루엣** 등 자유로운 모양으로 클리핑할 수 있습니다.

### 예시: 다이아몬드
```json
"shape": {
  "clipPath": "M50,0 L100,50 L50,100 L0,50 Z",
  "width": 100,
  "height": 100,
  "cornerRadius": 0,
  "elevation": 8
}
```

### 예시: 원형
```json
"shape": {
  "clipPath": "M50,0 A50,50 0 1,1 50,100 A50,50 0 1,1 50,0 Z",
  "width": 100,
  "height": 100,
  "elevation": 6
}
```

### 예시: 별
```json
"shape": {
  "clipPath": "M50,0 L61,35 L98,35 L68,57 L79,91 L50,70 L21,91 L32,57 L2,35 L39,35 Z",
  "width": 100,
  "height": 91,
  "elevation": 6
}
```

### clipPath 규칙
- `width`/`height`: path 좌표계의 기준 크기. 실제 오버레이 크기에 맞춰 자동 스케일링됨
- Android `Outline`은 **볼록(convex) 도형만 지원**. 오목한 모양은 시각적으로는 적용되지만 그림자가 사각형으로 폴백됨
- 투명 PNG(`overlay_bg.png`)와 함께 사용하면 더 정교한 모양 가능

---

## 커스텀 모양 오버레이 (투명 PNG)

SVG clipPath 대신 **투명 PNG**로 더 정교한 모양을 만들 수 있습니다.

### 방법
1. 원하는 모양의 PNG를 투명 배경으로 제작 (예: 곰돌이 실루엣)
2. 가운데에 텍스트가 들어갈 공간 확보
3. `overlay_bg.png`로 ZIP에 포함
4. `.cskin`에서 `"type": "image"` 설정

### 권장 사이즈
- **300x120px** ~ **400x160px** (가로형, 일반)
- **200x200px** (정사각/원형)
- **160x200px** (세로형, 캐릭터)

### 예시 .cskin (투명 PNG 조합)
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
- `elevation: 0` → 사각 그림자 제거 (투명 PNG 모양 살림)
- `padding` 조절로 텍스트가 모양 안쪽에 위치하도록 조정

---

## 주의사항

1. **색상**: `#` 포함 6자리 hex (`#FF0000`). 8자리 ARGB도 가능 (`#80FF0000`)
2. **solid**: `colors`에 1개
3. **gradient**: `colors`에 2개 이상
4. **배경 이미지**: 반드시 ZIP으로. base64 금지 (OOM 크래시)
5. **sectionOpacity**: 배경 이미지 사용 시 `0.6~0.8` 권장
6. **오버레이 배경 이미지**: ZIP에 `overlay_bg.png` → 오버레이에만 적용, `background.png` → 앱+오버레이 공유

---

## 예시 1: 네온 사이버펑크 (색상만)

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

## 예시 2: 디아블로 II (ZIP + 배경 이미지)

**ZIP 내용물:**
- `skin.cskin` — 아래 JSON
- `background.png` — 어두운 석조/불꽃 배경 (1080x2400)
- `overlay_bg.png` — 작은 돌 프레임 텍스처 (300x120, 투명 배경)

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
    "shape": {
      "cornerRadius": 2.0,
      "border": { "color": "#4A3B2B", "width": 2.0 },
      "elevation": 8.0
    }
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

## 예시 3: 곰돌이 오버레이 (clipPath)

```json
{
  "name": "곰돌이",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": {
      "type": "solid",
      "colors": ["#8B4513"],
      "opacity": 0.95
    },
    "text": {
      "color": "#FFFFFF",
      "shadow": { "color": "#000000", "radius": 2, "dx": 1, "dy": 1 }
    },
    "shape": {
      "clipPath": "M30,10 A15,15 0 0,1 30,40 L70,40 A15,15 0 0,1 70,10 L80,5 A10,10 0 0,1 90,15 L90,25 A10,10 0 0,1 80,35 L80,80 A20,20 0 0,1 60,100 L40,100 A20,20 0 0,1 20,80 L20,35 A10,10 0 0,1 10,25 L10,15 A10,10 0 0,1 20,5 Z",
      "width": 100,
      "height": 100,
      "elevation": 0
    },
    "padding": { "horizontal": 30, "vertical": 25 }
  },
  "app": {
    "backgroundColor": "#FDF5E6",
    "sectionColor": "#FFF8DC",
    "sectionOpacity": 0.85,
    "cardColor": "#FAEBD7",
    "textColor": "#3E1F09",
    "subtextColor": "#A0522D",
    "accentColor": "#D87093",
    "isDark": false
  },
  "widget": { "backgroundColor": "#8B4513", "opacity": 0.9 }
}
```

---

## 디자인 팁

- **다크 테마**: `isDark: true`, 배경 어둡게, 텍스트 밝게
- **라이트 테마**: `isDark: false`, 배경 밝게, 텍스트 어둡게
- **네온 효과**: `text.shadow.radius` 크게 (6~12), 형광색
- **미니멀**: `cornerRadius` 작게, `border` 없음, `elevation` 낮게
- **고급 스킨**: ZIP에 고퀄 배경 이미지 + `sectionOpacity` 0.7
- **커스텀 모양**: `clipPath`로 다이아몬드/별/원형, 또는 투명 PNG로 캐릭터 실루엣
- **오버레이 전용 배경**: `overlay_bg.png`로 앱 배경과 분리
- **앱 배경 비율**: **1080x2400** (9:20) 권장, centerCrop이라 어떤 비율이든 깨지지 않음
