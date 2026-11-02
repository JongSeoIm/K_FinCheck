package com.hk.stock.market;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

//국내 정규장 시간 판별 (평일 09:00~15:30). 공휴일·임시 휴장일은 아직 반영하지 않는다.
public final class MarketHours {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");
	public static final LocalTime OPEN = LocalTime.of(9, 0);
	public static final LocalTime CLOSE = LocalTime.of(15, 30);

	private MarketHours() {}

	public static ZonedDateTime now() {
		return ZonedDateTime.now(KST);
	}

	public static boolean isWeekday(ZonedDateTime time) {
		DayOfWeek day = time.getDayOfWeek();
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
	}

	public static boolean isOpen(ZonedDateTime time) {
		LocalTime t = time.toLocalTime();
		return isWeekday(time) && !t.isBefore(OPEN) && t.isBefore(CLOSE);
	}

	public static boolean isOpenNow() {
		return isOpen(now());
	}
}
