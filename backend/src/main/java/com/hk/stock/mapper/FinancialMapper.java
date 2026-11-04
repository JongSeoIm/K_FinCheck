package com.hk.stock.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.hk.stock.dtos.CompanyDto;
import com.hk.stock.dtos.FinancialReportDto;
import com.hk.stock.dtos.FinancialValueDto;

@Mapper
public interface FinancialMapper {
	public CompanyDto getCompany(String symbol);
	public int insertReport(FinancialReportDto report);
	public FinancialReportDto getReport(@Param("rceptNo") String rceptNo, @Param("fsDiv") String fsDiv);
	public List<FinancialReportDto> findReports(@Param("symbol") String symbol,
			@Param("bsnsYear") String bsnsYear, @Param("reprtCode") String reprtCode);
	public int insertValue(FinancialValueDto value);
	public List<FinancialValueDto> getValues(Long reportId);
}
