# CLAUDE.md - Claude Usage Widget

## 프로젝트 개요
Claude Max/Pro 사용량 모니터링 + AI API 비용 추적 위젯.
Android (Kotlin) + Desktop (Electron/Windows) 듀얼 플랫폼.

## 아키텍처

### 비용 추적 (Billing 중심 구조, v3.0+)
```
Billing API (실제 청구액) ──→ Primary
         ↓ 병합 (BillingApiClient)
오랑붕쌤 Drive (추정치) ───→ 보조/상세
         ↓
    위젯에 표시
```
- Billing API 키 있으면 → 실제 청구액 우선
- 없으면 → 오랑붕쌤 추정치 사용
- 둘 다 있으면 → Billing 우선 + 차이 표시
- AI별 소스 태그: ✓(실제) / ~(추정)

### 지원 AI Provider
| AI | Billing API | 키 저장 pref |
|----|-------------|-------------|
| Claude | Anthropic Admin API | `anthropic_admin_key` |
| GPT | OpenAI Billing API | `openai_admin_key` |
| Gemini | 미지원 (추정만) | `gemini_admin_key` |
| Grok | 미지원 (추정만) | `grok_admin_key` |
| Perplexity | 미지원 (추정만) | `perplexity_admin_key` |

새 AI에 Billing API 생기면 `BillingApiClient.kt`에 fetch 함수 추가하면 됨.

### 스킨 시스템
- 계절 4종: 봄/여름/가을/겨울
- 몽글몽글 5종: 핑크/퍼플/민트/옐로/스카이
- 기본 다크 + 사진 커스텀 (Desktop)
- Desktop: CSS 변수 (`data-skin` attribute)
- Android: `FloatingOverlay.SKIN_STYLES` + `MainActivity.SKINS`

### 구독 모델 (Subscription)
향후 확장용. `UsageData.kt`에 `Subscription` data class 정의됨.
AI별 구독료(Claude Max 등) 추적 준비 완료.

## 핵심 파일

### Android (`android/app/src/main/java/com/claudeusage/widget/`)
| 파일 | 역할 |
|------|------|
| `UsageData.kt` | 데이터 모델 (CostSource, ApiCostData, Subscription 등) |
| `BillingApiClient.kt` | Anthropic/OpenAI Billing API 통합 클라이언트 |
| `DriveApiClient.kt` | Google Drive에서 오랑붕쌤 추정 데이터 읽기 |
| `UsageMonitorService.kt` | 백그라운드 서비스 (스크래핑 + 비용 fetch) |
| `MainActivity.kt` | 설정 UI (접이식 섹션, 스킨, 동적 Billing 키) |
| `FloatingOverlay.kt` | 플로팅 오버레이 (스킨 적용) |
| `UsageWidgetProvider.kt` | 홈 위젯 |
| `KeyEncryption.kt` | AES-256-GCM 암호화 (PIN 기반) |
| `AppUpdater.kt` | GitHub Releases 자동 업데이트 |

### Desktop (`desktop/`)
| 파일 | 역할 |
|------|------|
| `main.js` | Electron 메인 (스크래핑, Drive, Billing, 트레이) |
| `preload.js` | IPC 브릿지 |
| `src/index.html/js` | 설정 UI |
| `src/widget.html/js` | 플로팅 위젯 |
| `src/styles.css` | 스킨 CSS 변수 정의 |

## 데이터 흐름
1. Claude 사용량: WebView로 `claude.ai/settings/usage` 스크래핑
2. API 비용: Billing API (primary) + Drive JSON (secondary) → `BillingApiClient.fetchAndMerge()`
3. Admin 키: AES-256-GCM 암호화 → SharedPreferences + Google Drive 백업

## 빌드 & 릴리즈
- Android: `gradle assembleRelease` (keystore 서명)
- Desktop: `electron-builder --win` (NSIS)
- 릴리즈: GitHub Actions `release.yml` (workflow_dispatch, version 입력)
- 자동 업데이트: `AppUpdater`가 GitHub Releases latest 체크

## 버전 규칙
- `versionCode`: 항상 이전보다 +1 (Android 업데이트 판단 기준)
- `versionName`: 자유 (릴리즈 워크플로우 version과 일치시킬 것)
- 파일: `android/app/build.gradle.kts` + `desktop/package.json`

## 시간대
시스템 로컬 시간대 사용 (`ZoneId.systemDefault()`, `toLocaleDateString('sv')`).
하드코딩 금지.
