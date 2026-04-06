# Claude Usage Widget

Claude API 사용량을 실시간으로 모니터링하는 데스크톱 위젯 + 안드로이드 앱

![Desktop](https://img.shields.io/badge/Desktop-Python%20Widget-blue)
![Android](https://img.shields.io/badge/Android-Persistent%20Notification-green)

## 구성

```
├── server/      # 사용량 추적 API 서버 (Flask)
├── desktop/     # 데스크톱 위젯 (Python + tkinter)
└── android/     # 안드로이드 앱 (Kotlin)
```

## 아키텍처

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│ Desktop      │────▶│ Usage Server │◀────│ Android App      │
│ Widget       │     │ (Flask API)  │     │ (Notification)   │
│ (tkinter)    │     │  :8490       │     │                  │
└─────────────┘     └──────┬───────┘     └──────────────────┘
                           │
                    ┌──────▼───────┐
                    │ Anthropic API│
                    │ + Local      │
                    │   Tracking   │
                    └──────────────┘
```

## 빠른 시작

### 1. 설정 파일 생성

```bash
cp config.example.json config.json
```

`config.json`을 편집하세요:

```json
{
  "anthropic_api_key": "sk-ant-your-key",
  "monthly_budget_usd": 100.00,
  "monthly_token_limit": 10000000,
  "alert_thresholds": [50, 75, 90, 95],
  "refresh_interval_seconds": 300,
  "server": {
    "host": "0.0.0.0",
    "port": 8490
  },
  "notification": {
    "ntfy_topic": "your-unique-topic",
    "ntfy_server": "https://ntfy.sh"
  }
}
```

### 2. 서버 실행

```bash
cd server
pip install -r requirements.txt
python server.py
```

서버는 `http://localhost:8490`에서 실행됩니다.

### 3. 데스크톱 위젯 실행

```bash
cd desktop
pip install -r requirements.txt
python main.py
```

화면 우상단에 항상 위에 표시되는 위젯이 나타납니다.

**위젯 기능:**
- 드래그로 위치 이동
- 접기/펼치기 (─ 버튼)
- 비용 & 토큰 사용량 프로그레스 바
- 사용량에 따라 색상 변화 (초록 → 노랑 → 빨강)

### 4. 안드로이드 앱

Android Studio에서 `android/` 디렉토리를 열어서 빌드하세요.

**앱 기능:**
- 상단 알림창에 사용량 상시 표시
- 프로그레스 바로 예산 사용률 표시
- 임계값 도달 시 알림 (50%, 75%, 90%, 95%)
- 부팅 시 자동 시작
- 서버 URL/갱신 주기 설정 가능

**알림에 표시되는 정보:**
- 현재 비용 / 예산 한도
- 토큰 사용량
- 입력/출력 토큰 구분

## API 엔드포인트

| Endpoint | Method | 설명 |
|---|---|---|
| `/api/usage` | GET | 현재 사용량 조회 |
| `/api/usage/track` | POST | 사용량 수동 기록 |
| `/api/usage/reset` | POST | 사용량 초기화 |
| `/api/config` | GET | 설정 조회 |
| `/api/health` | GET | 서버 상태 확인 |

### 사용량 수동 기록

API 호출 후 사용량을 추적하려면:

```bash
curl -X POST http://localhost:8490/api/usage/track \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-sonnet-4-6-20250514",
    "input_tokens": 1500,
    "output_tokens": 800
  }'
```

## ntfy.sh 푸시 알림 (선택)

[ntfy.sh](https://ntfy.sh)를 사용하면 서버 없이도 안드로이드에서 푸시 알림을 받을 수 있습니다.

1. 안드로이드에 ntfy 앱 설치
2. `config.json`에 고유한 토픽 이름 설정
3. ntfy 앱에서 같은 토픽 구독

임계값 초과 시 자동으로 푸시 알림이 전송됩니다.

## 모델별 가격 (참고)

| Model | Input (per 1M) | Output (per 1M) |
|---|---|---|
| Claude Opus 4.6 | $15.00 | $75.00 |
| Claude Sonnet 4.6 | $3.00 | $15.00 |
| Claude Haiku 4.5 | $0.80 | $4.00 |

## 라이선스

MIT License
