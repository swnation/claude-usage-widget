# 스킨 제작 가이드

## 개요
Android 위젯 앱의 커스텀 스킨. **플로팅 오버레이, 앱 화면, 홈 위젯, 알림** 4곳에 적용됨.

## 배포 형식

### 방법 1: ZIP 패키지 (권장 — 배경 이미지 포함)
```
myskin.zip
├── skin.cskin    ← 색상/스타일 JSON
└── background.png  ← 배경 이미지 (PNG/JPG/WebP)
```
- ZIP 안에 `.cskin` 또는 `.json` 파일 1개 + 이미지 파일 1개
- 앱이 자동으로 둘 다 추출하여 적용
- **배경 이미지는 해상도 자유** (base64 아님, OOM 없음)
- 이미지 권장: 1080x1920 이하, 1MB 이하

### 방법 2: .cskin 단일 파일 (색상만)
```
myskin.cskin  ← JSON 파일
```
- 배경 이미지 없이 색상/그라데이션만 적용
- 사용자가 앱에서 배경 이미지를 나중에 추가할 수 있음

> **중요**: `backgroundImage`에 base64를 직접 넣지 마세요. 고해상도 이미지는 수MB가 되어 앱이 크래시됩니다. 반드시 ZIP 패키지 방식을 사용하세요.

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
    "elevation": 10.0
  },
  "padding": { "horizontal": 20, "vertical": 16 }
}
```

| 필드 | 타입 | 설명 | 기본값 |
|------|------|------|--------|
| **background.type** | string | `"gradient"` (2+색), `"solid"` (단색), `"image"` (ZIP 내 이미지 사용) | `"gradient"` |
| **background.colors** | string[] | hex 색상 배열. solid=1개, gradient=2개+ | — |
| **background.direction** | string | 그라데이션 방향 (아래 표 참고) | `"tl_br"` |
| **background.opacity** | number | 투명도 0.0~1.0 | `0.92` |
| **text.color** | string | 텍스트 색상 | `"#e0e0e0"` |
| **text.shadow** | object | 텍스트 그림자. radius=0이면 없음 | 없음 |
| **shape.cornerRadius** | number | 모서리 둥글기 (dp) | `16` |
| **shape.border** | object | 테두리. width=0이면 없음 | 없음 |
| **shape.elevation** | number | 그림자 높이 (dp) | `4` |
| **padding** | object | 내부 여백 (px) | `24, 14` |

**그라데이션 방향:**

| 값 | 방향 |
|----|------|
| `tl_br` | 좌상→우하 (기본) |
| `top_bottom` | 위→아래 |
| `left_right` | 왼쪽→오른쪽 |
| `bl_tr` | 좌하→우상 |
| `tr_bl` | 우상→좌하 |
| `bottom_top` | 아래→위 |
| `right_left` | 오른쪽→왼쪽 |

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

| 필드 | 타입 | 설명 | 기본값 |
|------|------|------|--------|
| **backgroundColor** | string | 메인 배경색 (배경 이미지 없을 때) | `"#1a1a2e"` |
| **sectionColor** | string | 접이식 섹션 배경색 | `"#16213e"` |
| **sectionOpacity** | number | 섹션 투명도. 배경 이미지 사용 시 0.6~0.8 권장 | `1.0` |
| **cardColor** | string | 카드/입력 필드 배경색 | `"#22223a"` |
| **textColor** | string | 주요 텍스트 색상 | `"#e0e0e0"` |
| **subtextColor** | string | 보조 텍스트 색상 | `"#888899"` |
| **accentColor** | string | 강조색 (버튼, 섹션 제목, 라디오 등) | `"#c084fc"` |
| **isDark** | boolean | true: 밝은 텍스트 / false: 어두운 텍스트 | `true` |

> ZIP에 배경 이미지가 포함되어 있으면 `backgroundColor` 위에 이미지가 표시되고, 섹션은 `sectionOpacity`에 따라 반투명으로 렌더링됩니다.

---

## 3. `widget` — 홈 화면 위젯

```json
"widget": {
  "backgroundColor": "#1A1108",
  "opacity": 0.95
}
```

| 필드 | 타입 | 설명 | 기본값 |
|------|------|------|--------|
| **backgroundColor** | string | 위젯 배경색 | `"#1a1a2e"` |
| **opacity** | number | 위젯 투명도 | `0.87` |

---

## 주의사항

1. **색상**: 반드시 `#` 포함 6자리 hex (`#FF0000`). 8자리 ARGB도 가능 (`#80FF0000`)
2. **solid 타입**: `colors`에 1개만
3. **gradient 타입**: `colors`에 2개 이상
4. **배경 이미지**: 반드시 ZIP 패키지로. base64 직접 삽입 금지 (OOM 크래시)
5. **sectionOpacity**: 배경 이미지 사용 시 `0.6~0.8`이 텍스트 가독성과 배경 비침의 최적 밸런스
6. **오버레이 type="image"**: ZIP에 이미지가 있으면 오버레이 배경에도 자동 적용

---

## 예시 1: 네온 사이버펑크 (색상만, .cskin)

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

## 예시 2: 디아블로 II (ZIP 패키지, 배경 이미지 포함)

**ZIP 내용물:**
- `skin.cskin` — 아래 JSON
- `background.png` — 어두운 석조 텍스처/불꽃 배경 이미지 (1080x1920)

```json
{
  "name": "디아블로 II",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": {
      "type": "image",
      "opacity": 0.95
    },
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

## 예시 3: 몬치치 (ZIP 패키지, 밝은 테마)

**ZIP 내용물:**
- `skin.cskin` — 아래 JSON
- `background.jpg` — 귀여운 몬치치 캐릭터/털 질감 배경

```json
{
  "name": "몬치치",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": {
      "type": "solid",
      "colors": ["#8B4513"],
      "opacity": 0.9
    },
    "text": {
      "color": "#FFB6C1",
      "shadow": { "color": "#5C2E0E", "radius": 2.0, "dx": 1.0, "dy": 1.0 }
    },
    "shape": {
      "cornerRadius": 16.0,
      "border": { "color": "#D2B48C", "width": 3.0 },
      "elevation": 4.0
    }
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

---

## 디자인 팁

- **다크 테마**: `isDark: true`, 배경 어둡게, 텍스트 밝게
- **라이트 테마**: `isDark: false`, 배경 밝게, 텍스트 어둡게
- **네온 효과**: `text.shadow.radius`를 크게 (6~12), 형광색 사용
- **미니멀**: `cornerRadius` 작게, `border` 없음, `elevation` 낮게
- **고급 스킨**: ZIP에 고퀄 배경 이미지 포함 + `sectionOpacity` 0.7로 반투명
- **오버레이를 배경과 통일**: `overlay.background.type`을 `"image"`로 설정하면 ZIP의 이미지가 오버레이에도 적용됨
