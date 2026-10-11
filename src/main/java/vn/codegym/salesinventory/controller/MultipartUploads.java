package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.Locale;

/** Convert only multipart parsing failures to input errors; downstream failures remain server errors. */
final class MultipartUploads {
    private MultipartUploads() { }
    static boolean multipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }
    static Part read(HttpServletRequest request, String name, String missing, String tooLarge) throws IOException {
        if (!multipart(request)) throw new IllegalArgumentException(missing);
        try {
            return request.getPart(name);
        } catch (IllegalStateException invalid) {
            if (sizeLimit(invalid)) throw new IllegalArgumentException(tooLarge, invalid);
            throw invalid;
        } catch (ServletException invalid) {
            if (sizeLimit(invalid)) throw new IllegalArgumentException(tooLarge, invalid);
            throw new IllegalArgumentException("Dữ liệu tải lên không hợp lệ. Vui lòng chọn lại tệp.", invalid);
        } catch (IOException invalid) {
            if (sizeLimit(invalid)) throw new IllegalArgumentException(tooLarge, invalid);
            for (Throwable cause = invalid; cause != null; cause = cause.getCause()) {
                String type = cause.getClass().getName();
                if (type.contains(".fileupload.") && (type.endsWith(".FileUploadException") || type.endsWith("MalformedStreamException")))
                    throw new IllegalArgumentException("Dữ liệu tải lên không hợp lệ. Vui lòng chọn lại tệp.", invalid);
            }
            throw invalid;
        }
    }
    private static boolean sizeLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String type = cause.getClass().getName();
            if (type.endsWith("SizeLimitExceededException") || type.endsWith("FileSizeLimitExceededException")) return true;
        }
        return String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT).contains("size limit");
    }
}
