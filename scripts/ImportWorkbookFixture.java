import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import vn.codegym.salesinventory.service.UserImportService;
import vn.codegym.salesinventory.service.Xlsx;

class ImportWorkbookFixture {
    public static void main(String[] args) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        rows.add(UserImportService.HEADERS);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) rows.add(Arrays.asList(line.split("\t", -1)));
        }
        System.out.write(Xlsx.write(rows));
    }
}
