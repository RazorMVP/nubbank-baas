package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.product.RepaymentType;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class EmiCalculator {

    public List<ScheduleItem> generate(BigDecimal principal, BigDecimal annualRate,
                                        int numberOfRepayments, RepaymentType type,
                                        LocalDate startDate, int repaymentEvery, String frequency) {
        List<ScheduleItem> schedule = new ArrayList<>();
        if (type == RepaymentType.FLAT) {
            return generateFlat(principal, annualRate, numberOfRepayments, startDate, repaymentEvery, frequency);
        }
        // ANNUITY or DECLINING_BALANCE: EMI = P × r × (1+r)^n / ((1+r)^n - 1)
        double P = principal.doubleValue();
        double annualRateVal = annualRate.doubleValue();
        double periodsPerYear = periodsPerYear(frequency, repaymentEvery);
        double r = annualRateVal / 100.0 / periodsPerYear;
        double n = numberOfRepayments;
        double emi = (r == 0) ? P / n : P * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
        double balance = P;
        for (int i = 1; i <= numberOfRepayments; i++) {
            double interest = balance * r;
            double principalComp = (i == numberOfRepayments) ? balance : Math.min(emi - interest, balance);
            if (principalComp < 0) principalComp = 0;
            balance = Math.max(0, balance - principalComp);
            LocalDate dueDate = addPeriods(startDate, i, repaymentEvery, frequency);
            BigDecimal emiRounded = round(principalComp + interest);
            schedule.add(new ScheduleItem(i, round(principalComp), round(interest), emiRounded, dueDate));
        }
        return schedule;
    }

    private List<ScheduleItem> generateFlat(BigDecimal principal, BigDecimal annualRate,
                                              int n, LocalDate startDate, int repaymentEvery, String frequency) {
        List<ScheduleItem> schedule = new ArrayList<>();
        double P = principal.doubleValue();
        double periodsPerYear = periodsPerYear(frequency, repaymentEvery);
        double totalInterest = P * annualRate.doubleValue() / 100.0 * n / periodsPerYear;
        double installment = (P + totalInterest) / n;
        double principalPer = P / n;
        double interestPer = totalInterest / n;
        for (int i = 1; i <= n; i++) {
            LocalDate dueDate = addPeriods(startDate, i, repaymentEvery, frequency);
            schedule.add(new ScheduleItem(i, round(principalPer), round(interestPer), round(installment), dueDate));
        }
        return schedule;
    }

    private double periodsPerYear(String frequency, int repaymentEvery) {
        return switch (frequency.toUpperCase()) {
            case "WEEKS" -> 52.0 / repaymentEvery;
            case "DAYS" -> 365.0 / repaymentEvery;
            default -> 12.0 / repaymentEvery; // MONTHS
        };
    }

    private LocalDate addPeriods(LocalDate base, int n, int every, String frequency) {
        int totalUnits = n * every;
        return switch (frequency.toUpperCase()) {
            case "WEEKS" -> base.plusWeeks(totalUnits);
            case "DAYS" -> base.plusDays(totalUnits);
            default -> base.plusMonths(totalUnits);
        };
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    public record ScheduleItem(
        int installmentNo, BigDecimal principalDue,
        BigDecimal interestDue, BigDecimal totalDue, LocalDate dueDate
    ) {}
}
