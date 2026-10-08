package br.com.ged.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffCalculatorTest {

    private final DiffCalculator calculator = new DiffCalculator();

    @Test
    void detectaLinhaAdicionadaEntreParagrafos() {
        List<DiffLine> diff = calculator.calculate(
                List.of("paragrafo um", "paragrafo dois"),
                List.of("paragrafo um", "paragrafo novo", "paragrafo dois"));

        assertEquals(List.of(
                new DiffLine(DiffType.UNCHANGED, "paragrafo um"),
                new DiffLine(DiffType.ADDED, "paragrafo novo"),
                new DiffLine(DiffType.UNCHANGED, "paragrafo dois")), diff);
    }

    @Test
    void mapeiaValorAlteradoComoRemocaoSeguidaDeAdicao() {
        List<DiffLine> diff = calculator.calculate(
                List.of("total: 100"),
                List.of("total: 150"));

        assertEquals(List.of(
                new DiffLine(DiffType.REMOVED, "total: 100"),
                new DiffLine(DiffType.ADDED, "total: 150")), diff);
    }

    @Test
    void textosIdenticosGeraSomenteLinhasInalteradas() {
        List<DiffLine> diff = calculator.calculate(
                List.of("um", "dois"),
                List.of("um", "dois"));

        assertEquals(List.of(
                new DiffLine(DiffType.UNCHANGED, "um"),
                new DiffLine(DiffType.UNCHANGED, "dois")), diff);
    }

    @Test
    void variasMudancasMantemOrdemDeLeituraDoArquivo() {
        List<DiffLine> diff = calculator.calculate(
                List.of("cabecalho", "valor antigo", "rodape", "obs: nada"),
                List.of("cabecalho", "valor novo", "meio novo", "rodape", "obs: atualizado"));

        assertEquals(List.of(
                new DiffLine(DiffType.UNCHANGED, "cabecalho"),
                new DiffLine(DiffType.REMOVED, "valor antigo"),
                new DiffLine(DiffType.ADDED, "valor novo"),
                new DiffLine(DiffType.ADDED, "meio novo"),
                new DiffLine(DiffType.UNCHANGED, "rodape"),
                new DiffLine(DiffType.REMOVED, "obs: nada"),
                new DiffLine(DiffType.ADDED, "obs: atualizado")), diff);
    }
}
