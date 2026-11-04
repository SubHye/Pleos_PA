// What clients read about each tool. Names and properties come from the catalog; only the wording lives here.
const READ_ONCE =
  '호출할 때 한 번 읽습니다. 항목마다 status를 확인하고, status가 OK가 아닌 항목의 값을 추정하거나 0, 꺼짐, 닫힘으로 말하지 마세요.';

const text = (title: string, what: string) => ({ title, description: `${what} ${READ_ONCE}` });

export const TOOL_TEXTS: Readonly<Record<string, { title: string; description: string }>> = Object.freeze({
  get_door_status: text(
    '문 상태',
    '문별 열림 위치와 문 잠금 상태를 조회합니다. 잠금은 차량이 보고한 묶음 영역으로 나옵니다.',
  ),
  get_window_status: text(
    '창문 위치',
    '창문별 위치 원시값을 조회합니다. 값의 범위는 capability의 minimum, maximum을 따르며 퍼센트가 아닙니다.',
  ),
  get_energy_level: text(
    '에너지 잔량',
    '연료 잔량과 EV 배터리 잔량을 퍼센트로 조회합니다. 차량 동력원에 맞지 않는 항목은 미지원으로 나옵니다. 주행 가능 거리로 환산하지 마세요.',
  ),
  get_front_left_door_position: {
    title: '앞좌측 문 위치',
    description:
      '앞좌측 문 하나의 열림 상태를 조회합니다. 호출할 때 한 번 읽습니다. ok가 true일 때만 state를 말하고, ok가 false이면 값을 추정하거나 닫힘으로 말하지 마세요.',
  },
  get_brake_status: text('브레이크 페달', '브레이크 페달을 밟고 있는지 조회합니다.'),
  get_display_units: text('표시 단위', '온도 표시 단위를 조회합니다.'),
  get_glove_box_status: text('글로브박스', '글로브박스 잠금 상태를 조회합니다.'),
  get_driving_mode_status: text('주행 모드', '주행 모드와 크루즈 컨트롤 상태, 목표 속도(m/s)를 조회합니다.'),
  get_ev_status: text('EV 상태', '전비(km/kWh), 충전 전류 제한(A), 충전 스위치 상태를 조회합니다.'),
  get_fuel_economy: text('연비', '순간, 충전 이후, 누적, 현재 연비(L/100 km)를 조회합니다.'),
  get_hvac_status: text(
    '공조 상태',
    '공조 전원, 에어컨, 오토, 내외기, 제상, 바람 세기와 방향, 설정 온도와 실내 온도(°C)를 조회합니다.',
  ),
  get_light_status: text('조명 상태', '실내등, 독서등, 앰비언트, 전조등, 후미등, 안개등의 스위치와 상태를 조회합니다.'),
  get_trip_status: text(
    '주행 기록',
    '누적 주행거리계(DISTANCE_DRIVEN, km)와 이번 주행, 누적, 주유 이후의 주행 시간(분)과 거리(m)를 조회합니다. 이번 주행 거리는 TRIP_CURRENT_DRIVING_DISTANCE입니다.',
  ),
  get_safety_status: text(
    '안전 상태',
    '오일과 워셔액 수준, 에어백, 안전벨트 미착용 경고, 전원 차단 경고를 조회합니다. SEAT_AIRBAG_ENABLED의 true는 API 문서상 해당 좌석 에어백의 문제이며 이름과 반대로 읽힐 수 있고, POWER_DOWN_WARNING_INDICATOR의 true는 API 문서상 엔진 문제입니다. 실행으로 대조하지 않았으므로 단정하지 마세요.',
  ),
  get_seat_status: text(
    '좌석 상태',
    '좌석 점유, 안전벨트 체결, 좌석 위치 원시값, 열선과 통풍 단계를 조회합니다. 에뮬레이터 대조에서 SEAT_OCCUPANCY는 Console과 반대로 나왔고 원인을 확인하지 못했으므로 이 값을 확정된 사실로 말하지 마세요. 좌석 위치는 에뮬레이터에서 값을 대조하지 못했습니다.',
  ),
  get_side_mirror_status: text('사이드미러', '사이드미러 열선 단계를 조회합니다.'),
  get_steering_wheel_status: text(
    '스티어링 휠',
    '스티어링 휠 열선 단계와 앞바퀴 조향각(도)을 조회합니다. 조향각은 핸들 회전각이 아닙니다. 에뮬레이터 대조에서 STEERING_WHEEL_ANGLE은 Console과 부호가 반대로 나왔고 원인을 확인하지 못했으므로 회전 방향을 단정하지 마세요.',
  ),
  get_tire_status: text(
    '저압 임계값',
    '바퀴별 저압 경고 임계값(kPa)을 조회합니다. 현재 타이어 공기압이 아니므로 현재 공기압으로 말하지 마세요.',
  ),
  get_toll_status: text('하이패스', '하이패스 안내 음량을 조회합니다.'),
  get_turn_signal_status: text('방향지시등', '방향지시등 상태를 조회합니다.'),
  get_window_detail_status: text(
    '창문 상세',
    '창문 잠금, 블라인드 위치, 스마트 글라스 상태와 단계를 조회합니다. SMART_GLASS_CONFIG는 읽을 수 없어 항상 실패로 나옵니다.',
  ),
  get_wiper_status: text('와이퍼', '와이퍼 상태와 스위치를 조회합니다.'),
});
