# Spendify - your finanical tracker
___
[![standard-readme compliant](https://img.shields.io/badge/readme%20style-standard-brightgreen.svg?style=flat-square)](https://github.com/RichardLitt/standard-readme)

> Automated expense tracking for Hong Kong's digital financial ecosystem. Spendify combines optical character recognition (OCR), machine learning, and budget analytics to help young professionals manage personal finances in a high-cost urban environment.

## Table of Content
___

- [Background](#background)
- [Install](#install)

## Background
___
Hong Kong presents unique financial management challenges for young professionals: high housing costs, frequent small-value transactions across multiple platforms (Octopus, Alipay, credit cards), cultural dining-out habits, and limited financial literacy resources. Existing tools like Planto, gini, and Wallet by BudgetBakers provide partial solutions but rely on manual input or bank data integration—missing the fragmented, cashless transaction patterns common in Hong Kong.

Spendify addresses this gap through:
- Automated receipt scanning using Tesseract OCR to extract transaction details from receipt images
- Smart categorization via machine learning to classify expenses and identify spending patterns
- Real-time budget alerts with predictive analytics to prevent overspending
- Interactive visual reports showing monthly trends, category breakdowns, and spending insights
- Privacy-first architecture processing receipts locally to comply with Hong Kong's Personal Data (Privacy) Ordinance (PDPO)

The application targets Hong Kong's digital-first population with a cross-platform mobile app (iOS/Android) and web interface, enabling instant receipt capture and centralized expense tracking across all transaction platforms.

## Features  
___
### Receipt Scanning & OCR
- Real-time receipt capture via mobile camera
- Automated text extraction using Tesseract OCR
- Field extraction: date, amount, merchant, tax, payment method
- Support for thermal and printed receipts
- Preprocessing pipeline: image enhancement, deskewing, noise reduction

### Expense Management
- Automated expense categorization via machine learning
- Manual editing of extracted data for accuracy
- Custom category creation and management
- Transaction history with filtering by date range and category
- Bulk import support for multiple receipts

### Budget Analytics
- Monthly budget target setting with real-time progress tracking
- Budget threshold alerts (warning at 80%, critical at 100%)
- Visual spending dashboard: bar charts, pie charts, trends
- Category-level spending breakdown
- Monthly, quarterly, and yearly comparisons

### User Experience
- Cross-platform support: iOS, Android, and web
- Responsive design optimized for mobile-first interaction
- Dark mode and light mode support
- Bottom navigation for quick access to core features
- Offline capability for receipt scanning (sync on connection)

### Security & Privacy
- Secure authentication via OAuth 2.0 and JWT tokens
- End-to-end encryption for sensitive data transmission
- Local OCR processing—no receipt data sent to external services
- PDPO-compliant data handling
- Role-based access control (RBAC)

## Install
___
### Prerequisites
- JDK 17+ (for backend)
- Microsoft SQL Server 2019+ or compatible instance
- Maven 3.8+ (for backend build)
- Git for version control


### Backend Setup
1. Clone the repository:  
``` bash
git clone https://github.com/Maulei41/Spendify.git
cd Spendify/backend
```
2. Configure database connection:
``` text
spring.datasource.url=jdbc:sqlserver://your-server:1433;databaseName=spendify;encrypt=true;trustServerCertificate=false
spring.datasource.username=your-username
spring.datasource.password=your-password
spring.datasource.driverClassName=com.microsoft.sqlserver.jdbc.SQLServerDriver
```
3. Build and run:
``` bash
./mvnw clean install
./mvnw spring-boot:run
```
The backend server will start on `http://localhost:8080`.
4. Initialize database schema:

The application will automatically run schema migration scripts on startup. For manual setup, execute SQL scripts in src/main/resources/db/:
```sql
-- SQL Server
CREATE DATABASE spendify;
-- Run migration scripts
```

## API endpoint
___
Base URL: `http://localhost:8080/`
### Authentication
- `POST /auth/register` - Register new user
- `POST /auth/login` - Login with credentials
- `POST /auth/refresh` - Refresh JWT token
- `POST /auth/logout` - Logout
- `GET /auth/me` - User information





