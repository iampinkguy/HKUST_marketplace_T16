# HKUST Marketplace — Task notes

How the application meets the non-honours requirements. Accounts, listings, deals, reviews, favourites, and notifications are in `data.db`. Listing chat is in `chat_history.json`, keyed by listing id and then by buyer username.

## Password rules

These rules apply to every new or changed password:

- The password cannot be empty.
- It must be at least 8 characters.
- It must contain at least one uppercase letter, one lowercase letter, and one digit.
- It is stored as a BCrypt hash with cost 12. The plaintext password is not stored.

The seeded demo passwords `admin123` and `password1` are already hashed and can still sign in. They are rejected when someone tries to set a new password.

## Non-functional requirements

Buyer, seller, and admin screens share the same sidebar, cards, chat layout, notification cards, and profile form. Role checks run in `DataService` before an operation, so a buyer session cannot call seller-only or admin-only methods.

## Technical requirements

`DatabaseManager.initialize()` creates the SQLite tables. Opening a listing detail increments that listing's view count. Searching a chat filters the retained messages and does not delete them. A giveaway claim updates the listing from live and available to given inside one transaction, so a second claim of the same item fails.

## Task 1 — Buyer portal

Screens: `BuyerRegisterController`, `BuyerLoginController`, `BuyerDashboardController`.

### 1.1 Registration

The buyer enters a username, full name, password, confirm password, and an optional residence hall. The username must be unique across buyers, sellers, and admins. An empty full name, a weak password, a mismatched confirm password, or a duplicate username is shown as an error. Implemented by `DataService.registerBuyer`.

### 1.2 Login

The buyer enters a username and password. Enter submits the form, and Show / Hide reveals the same password. A seller or admin account is rejected on this screen. Implemented by `DataService.authenticate`.

### 1.3 Browse marketplace

Only live listings are shown. Each card shows the title, seller, category, type, price or borrow/free label, condition, meet-up location, posted date, availability, and the first photo. The buyer can search, filter by category, type, condition, and price, and sort by newest or by price. Implemented by `DataService.searchBuyerMarketplace`.

### 1.4 Listing detail

The page shows up to six photos, the description, the listing fields, the seller name and average rating, and availability. Sell, Lend, and Giveaway listings show the matching action, plus Chat and Report Listing. A lend listing also shows the seller's lending period. Check Seller opens a dialog titled `Seller Profile - ` plus the seller's full name, with the rating, reviews, and bio, or `This seller doesn't have a bio.`

### 1.5 Offer, borrow, claim, and chat

Sell submits a price. Lend submits a start and end. The requested length must be at least 5 seconds and no longer than the seller's lending period. Giveaway claims the item in one transaction. Chat is private to that listing and that buyer. History is kept and can be searched.

### 1.6 My Deals

Ongoing and completed offers, borrows, and claims are listed. View listing and Chat reopen the detail page and the thread. An accepted borrow shows the due date and Self-return before the due time. When the due time has passed, the item is returned automatically. Purchased and claimed items can be marked complete, and the buyer is then asked to rate the seller.

### 1.7 Manage profile

The buyer can change full name and password. The new password uses the rules above, and the confirmation must match.

### 1.8 Notification board

Notices are timestamped cards with a category, including accepted, declined, and countered offers, chat messages, borrow reminders, listing removal, seller replies, and announcements. Go opens the related deal or listing. Read All Notifications marks every notice for this buyer as read.

### 1.9 Deal history

Completed deals are cards showing the item, seller, date, type (Bought, Borrowed, or Received), and outcome. The buyer can filter by date range, category, and seller, and can search by title. Opening a card shows the listing.

### 1.10 Review and rate sellers

After a completed purchase, claim, or returned borrow, the buyer submits 1 to 5 stars and an optional comment. The average and the reviews appear on listing details and in Check Seller.

## Task 2 — Seller portal

Screens: `SellerRegisterController`, `SellerLoginController`, `SellerDashboardController`.

### 2.1 Registration

The seller enters a unique username, full name, password, confirm password, an optional preferred meet-up location, and an optional bio. Validation and password storage match Task 1.1.

### 2.2 Login

Same sign-in behaviour as Task 1.2. A buyer or admin account cannot open the seller portal.

### 2.3 Create new listing

The seller chooses Sell, Lend, or Give Away first. Price is required only for Sell. Lending duration is required only for Lend. Up to six photos can be attached. Submission stores the listing as Pending Review and notifies the admin.

### 2.4 My Listings

The seller sees title, category, type, and status. Edit and Delete ask for confirmation. A listing with an open request, or one that is already Reserved, Sold, Lent, or Given, cannot be changed as if it were still free.

### 2.5 Offer and request management

Incoming offers, borrow requests, and claims are listed on the listing detail page. The seller can view the price or proposed dates, accept, decline, counter a sale or lend request, and open the listing chat. Accepting updates the listing status and notifies the buyer.

### 2.6 Manage profile

The seller can change full name, password, bio, and preferred meet-up location. Password changes use the rules above.

### 2.7 Notification board

Cards include listing approval and rejection, new offers and requests, ratings, buyer completion, and announcements. Go opens the seller's listing. Read All Notifications marks every notice for this seller as read.

### 2.8 Seller stats

The page shows total views, offers received, completed deals, and the average rating. Views are the sum of each listing's view count. Listings by category are a bar chart. Listing status is a pie chart. Counts come from `DataService.getSellerActivityStats`.

### 2.9 Review and feedback handling

The seller can reply to a review, which notifies that buyer, or flag a review for admin moderation. Implemented by `DataService.replyToReview` and `DataService.flagReview`.

## Task 3 — Admin portal

There is no admin registration screen. The seeded account is username `admin` and password `admin123`. Screens: `AdminLoginController`, `AdminDashboardController`. Account changes are recorded by `AdminUserService`.

### 3.1 Login

Login only, checked against the seeded admin record. A buyer or seller account cannot open the admin portal.

### 3.2 Listing review and approval

Pending submissions show the title, seller, category, photos, type, meet-up location, submitted date, and status. Approve publishes the listing. Reject requires a written reason and notifies the seller.

### 3.3 Manage all users

The admin can view, edit, deactivate, or permanently delete a buyer or seller. Deactivate blocks sign-in and keeps listings, deals, and chats. Permanent deletion removes the login and profile, withdraws that user's listings, and keeps deals, chats, and an audit row. Existing deals are not cancelled automatically.

### 3.4 Manage own profile

The admin can change full name and password. Clear Database, after confirmation, empties marketplace rows and `chat_history.json` through `DatabaseManager.clearDatabase`. Active admin accounts are kept.

### 3.5 All deals record

Accepted deals show the item, buyer, seller, type, start, return or completion, and status. The admin can search and filter by type and status.

### 3.6 Notification board

Cards include new submissions, listing reports, account changes, and announcements. Read All Notifications marks every notice for this admin as read.

### 3.7 Manage all listings

The admin can edit any listing, add a listing for a seller, and take down a live listing with a reason. Buyers who still have an open request on that listing are notified.

### 3.8 Seller ratings overview

All buyer-to-seller ratings are listed. The admin can filter by user, score, and date, and can hide an inappropriate rating. Hidden ratings stay stored and are left out of the public average.
