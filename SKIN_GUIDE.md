# .cskin 스킨 제작 가이드

## 개요
Android 위젯 앱의 커스텀 스킨 파일. 확장자 `.cskin`, 내부는 JSON.
**플로팅 오버레이, 앱 화면, 홈 위젯, 알림** 4곳에 모두 적용됨.

## 파일 구조

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

## 1. `overlay` — 플로팅 오버레이 (화면 위에 떠 있는 작은 표시창)

```json
"overlay": {
  "background": {
    "type": "gradient | solid | image",
    "colors": ["#hex1", "#hex2"],
    "direction": "tl_br | top_bottom | left_right | bl_tr | tr_bl | bottom_top | right_left",
    "image": "base64 PNG/JPG (type이 image일 때)",
    "opacity": 0.92
  },
  "text": {
    "color": "#hex",
    "shadow": {
      "color": "#hex",
      "radius": 4.0,
      "dx": 1.0,
      "dy": 1.0
    }
  },
  "shape": {
    "cornerRadius": 16.0,
    "border": {
      "color": "#hex",
      "width": 1.5
    },
    "elevation": 4.0
  },
  "padding": {
    "horizontal": 24,
    "vertical": 14
  }
}
```

| 필드 | 설명 | 기본값 |
|------|------|--------|
| `background.type` | `gradient` (2+색 그라데이션), `solid` (단색), `image` (base64 이미지) | `gradient` |
| `background.colors` | hex 색상 배열. solid는 1개, gradient는 2개 이상 | — |
| `background.direction` | 그라데이션 방향 | `tl_br` |
| `background.opacity` | 배경 투명도 (0.0 투명 ~ 1.0 불투명) | `0.92` |
| `text.color` | 텍스트 색상 | `#e0e0e0` |
| `text.shadow` | 텍스트 그림자 (radius=0이면 없음) | 없음 |
| `shape.cornerRadius` | 모서리 둥글기 (dp) | `16` |
| `shape.border` | 테두리 (width=0이면 없음) | 없음 |
| `shape.elevation` | 그림자 높이 (dp) | `4` |
| `padding` | 내부 여백 (px) | `24, 14` |

---

## 2. `app` — 앱 메인 화면

```json
"app": {
  "backgroundColor": "#hex",
  "backgroundImage": "base64 PNG/JPG (전체 배경 이미지)",
  "sectionColor": "#hex",
  "sectionOpacity": 0.7,
  "cardColor": "#hex",
  "textColor": "#hex",
  "subtextColor": "#hex",
  "accentColor": "#hex",
  "isDark": true
}
```

| 필드 | 설명 | 기본값 |
|------|------|--------|
| `backgroundColor` | 앱 메인 배경색 | `#1a1a2e` |
| `backgroundImage` | **base64 배경 이미지** (있으면 backgroundColor 위에 표시) | 없음 |
| `sectionColor` | 접이식 섹션 헤더/바디 배경색 | `#16213e` |
| `sectionOpacity` | 섹션 투명도 (배경 이미지 사용 시 0.7 권장) | `1.0` |
| `cardColor` | 카드/입력 필드 배경색 | `#22223a` |
| `textColor` | 주요 텍스트 색상 | `#e0e0e0` |
| `subtextColor` | 보조 텍스트/라벨 색상 | `#888899` |
| `accentColor` | **강조색** — 버튼, 섹션 제목, 라디오 버튼 등 | `#c084fc` |
| `isDark` | `true`: 밝은 텍스트 / `false`: 어두운 텍스트 | `true` |

---

## 3. `widget` — 홈 화면 위젯

```json
"widget": {
  "backgroundColor": "#hex",
  "opacity": 0.87
}
```

| 필드 | 설명 | 기본값 |
|------|------|--------|
| `backgroundColor` | 위젯 배경색 | `#1a1a2e` |
| `opacity` | 위젯 투명도 | `0.87` |

---

## 주의사항

1. **색상**: 반드시 `#` 포함 6자리 hex (`#FF0000`). 8자리 ARGB도 가능 (`#80FF0000`)
2. **solid 타입**: `colors`에 색상 1개만 넣으면 됨
3. **gradient 타입**: `colors`에 2개 이상 필수
4. **base64 이미지**: 큰 이미지는 파일 크기 증가. 500KB 이하 권장
5. **sectionOpacity**: `backgroundImage` 사용 시 `0.6~0.8` 사이가 배경이 비쳐 보이면서 텍스트도 읽힘

---

## 예시: 색상만 사용 (간단)

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
  "widget": {
    "backgroundColor": "#0a0820",
    "opacity": 0.9
  }
}
```

## 예시: 배경 이미지 포함 (고급)

```json
{
  "name": "디아블로 II",
  "author": "AI Designer",
  "version": 1,
  "overlay": {
    "background": {
      "type": "solid",
      "colors": ["#1A1108"],
      "opacity": 0.95
    },
    "text": {
      "color": "#8B0000",
      "shadow": { "color": "#000000", "radius": 4.0, "dx": 2.0, "dy": 2.0 }
    },
    "shape": {
      "cornerRadius": 0.0,
      "border": { "color": "#A9A9A9", "width": 2.0 },
      "elevation": 8.0
    }
  },
  "app": {
    "backgroundColor": "#0D0804",
    "backgroundImage": "(여기에 base64 인코딩된 텍스처/배경 이미지)",
    "sectionColor": "#1A1108",
    "sectionOpacity": 0.75,
    "cardColor": "#2A1B12",
    "textColor": "#D4B895",
    "subtextColor": "#8C7355",
    "accentColor": "#8B0000",
    "isDark": true
  },
  "widget": {
    "backgroundColor": "#1A1108",
    "opacity": 0.95
  }
}
```

---

## 디자인 팁

- **다크 테마**: `isDark: true`, 배경 어둡게, 텍스트 밝게
- **라이트 테마**: `isDark: false`, 배경 밝게, 텍스트 어둡게
- **네온 효과**: `text.shadow.radius`를 크게 (6~12), 형광색 사용
- **미니멀**: `cornerRadius` 작게, `border` 없음, `elevation` 낮게
- **배경 이미지 사용 시**: `sectionOpacity`를 `0.6~0.8`로 설정해야 텍스트 가독성 유지
