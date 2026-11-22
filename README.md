# HKUST Marketplace — Team 16

A **JavaFX + Maven** desktop application for buying, selling, lending, and giving away items on campus. The Buyer, Seller, and Admin portals share a local SQLite database.

## Prerequisites

1. **JDK 17 or newer.** Install a full JDK, then open a new terminal and check that both commands report version 17 or newer. [Download a JDK](https://adoptium.net/)

   ```powershell
   java -version
   javac -version
   ```

2. **Maven is optional.** The project includes the Maven Wrapper. The first run needs internet access to download Maven and project dependencies.

## How to Run (Windows)

Open **PowerShell** in the project folder containing `pom.xml` and run the following command.

```powershell
.\mvnw.cmd clean javafx:run
```

The welcome screen lets you choose Buyer, Seller, or Admin. On first launch, the app creates `data.db` and loads the demo accounts and listings. On macOS or Linux, use `sh mvnw clean javafx:run` from the same folder.

### Alternative if Maven is on your PATH

```powershell
mvn clean javafx:run
```

### Run from IntelliJ IDEA

1. Open the project folder as a Maven project.
2. Select JDK 17 or newer as the project SDK and let Maven load the dependencies.
3. Run the `javafx:run` goal from the Maven tool window.

### Run from VS Code

1. Install **Extension Pack for Java**.
2. Open the folder containing `pom.xml`.
3. Run the wrapper command above in the integrated terminal.

## Demo Credentials

Choose the portal matching the account’s role on the welcome screen.

| Role   | Username  | Password    |
|--------|-----------|-------------|
| Admin  | `admin`   | `admin123`  |
| Buyer  | `alice`   | `password1` |
| Buyer  | `bob`     | `password1` |
| Seller | `charlie` | `password1` |
| Seller | `diana`   | `password1` |

## Features Implemented

### Shared

- Role-specific sign-in and BCrypt password storage.
- SQLite stores accounts, listings, requests, reviews, favourites, and notifications. Listing photos are copied into `uploads/`, and the default chat history file is `chat_history.json`. These paths are relative to the working directory.

### Buyer Portal

- Register, browse listings, save favourites, and message sellers.
- Make purchase offers, request loans, claim giveaways, track deals, and rate sellers after completion.

### Seller Portal

- Register, submit listings with photos for review, and manage them in **My Listings**.
- Respond to offers and requests, mark deals complete, and view statistics, reviews, and notifications.

### Admin Portal

- Approve or reject submissions, edit published listings, and take listings down.
- Manage user accounts, inspect deals and seller ratings, and follow up on notifications.

## Project Structure

```text
HKUST_marketplace_T16/
├── mvnw / mvnw.cmd
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/hkust/marketplace/
    │   │   ├── MarketplaceApp.java
    │   │   ├── controllers/
    │   │   ├── models/
    │   │   └── utils/
    │   └── resources/
    │       ├── fxml/
    │       ├── css/styles.css
    │       └── images/
    └── test/java/com/hkust/marketplace/
```

## Troubleshooting

### `mvn` is not recognized

Use the included wrapper from the project folder.

```powershell
.\mvnw.cmd clean javafx:run
```

### `java` or `javac` is not recognized

Set `JAVA_HOME` to your JDK installation, add its `bin` folder to `PATH`, and restart the terminal.

### JavaFX reports a display error

Launch the app and the optional JavaFX screen tests in a graphical desktop session.

### To see the underlying error

```powershell
.\mvnw.cmd clean javafx:run -e
```

Look for the first relevant `Caused by` line in the Maven output.

## For the TA — Grading Guide

### Follow a sale across all three portals

Start from a fresh checkout for the seeded **Mini Fridge** example. Sign out between roles. Restarting from the same folder preserves your progress.

1. **Admin (`admin`).** Open **Listing Review**, select Diana’s **Mini Fridge**, and approve it. The listing is published and Diana receives an approval notification.
2. **Buyer (`alice`).** Find **Mini Fridge** in **Browse Marketplace**. Open it, choose **Make Offer**, and submit a price such as HK$350.
3. **Seller (`diana`).** Open **Mini Fridge** in **My Listings**. Under **Incoming offers & requests**, check Alice’s price and select **Accept**. Reopen the listing and choose **Mark Complete**. The sale stays open until Alice also confirms.
4. **Buyer (`alice`).** Open **My Deals** and choose **Mark complete** for the fridge. The app confirms completion and prompts Alice to rate the seller.

To check the other listing types, use **Graphing Calculator TI-84** for a borrow request and **IKEA Desk Lamp** for a giveaway claim.

### Run the automated tests

Run the default JUnit suite from the project folder. Maven writes the detailed results to `target/surefire-reports/`.

```powershell
.\mvnw.cmd test
```

To include the JavaFX admin screen tests, run the suite in a desktop session with the opt-in flag.

```powershell
.\mvnw.cmd "-Dmarketplace.uiTests=true" test
```

On macOS or Linux, replace `.\mvnw.cmd` with `sh mvnw`.

**Verified result at `413bffd` (JDK 21, Windows).** The default run reported 117 passed, 1 failed, and 6 skipped. The six skips are the opt-in JavaFX screen test classes. The failing test in `AdminRatingsOverviewTest` expected a legacy review with no date to stay undated, but it received a timestamp.

## Licence

Educational use — HKUST COMP project.
