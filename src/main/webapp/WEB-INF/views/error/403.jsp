<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>403 - Không đủ quyền truy cập</title>
    <style>
        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background-color: #f8f9fa; }
        .box { background: #fff; padding: 40px; border-radius: 8px; display: inline-block; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
        h1 { font-size: 72px; color: #ffc107; margin: 0; }
        h2 { color: #333; }
        p { color: #6c757d; margin-bottom: 25px; }
        .btn { padding: 10px 20px; background-color: #0d6efd; color: white; text-decoration: none; border-radius: 5px; font-weight: bold; }
        .btn:hover { background-color: #0b5ed7; }
    </style>
</head>
<body>
    <div class="box">
        <h1>403</h1>
        <h2>Không đủ quyền truy cập</h2>
        <p>Bạn không có quyền truy cập vào chức năng này. Vui lòng quay lại.</p>
        <a href="${pageContext.request.contextPath}/" class="btn">Quay lại trang chủ</a>
    </div>
</body>
</html>