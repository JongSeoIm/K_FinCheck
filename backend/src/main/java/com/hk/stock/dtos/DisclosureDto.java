package com.hk.stock.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;

//OpenDART 공시 1건 (공시 목록 list.json)
@Data
@AllArgsConstructor
public class DisclosureDto {
	private String rceptNo;		//접수번호
	private String reportName;	//공시 이름
	private String filedAt;		//접수일 (2026-11-05)
	private String submitter;	//제출인
	private String url;			//DART 공시 원문 주소
}
