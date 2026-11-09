# Task2 Seller Use case specification

## Use Case: Register Account

**Brief Description:** Allows a new user to create a seller account to list items on the
marketplace.

**Use-case Diagram:**

**Preconditions:**

● The user is not currently logged into an existing account.
● The user has valid personal information (name, unique username, password) to
provide.

**Postconditions:**

● A new Seller account is successfully created and stored securely (hashed
password) in the database.
● The system state reflects a new registered user, and the user is redirected to the
login screen.

**Basic Flow:**

1. The use case begins when the user chooses to create a new account as a seller.
2. The system displays the interface for inputting personal information.
**{Enter Personal Information}**
3. The Seller fills in a username, their full name, password, confirm-password, and
optionally chooses preferred meet-up location(s) and a seller bio.
4. The Seller clicks the [Confirm] button to submit the personal information.
**{Username Validation}**
5. The system validates the username to ensure its uniqueness.
**{Password Validation}**
6. The system validates the password by checking if it meets the validation rules.
**{Confirm-password Validation}**
7. The system validates the confirm-password by checking if it has the same value
as the password.
8. The system securely hashes the password and appends the entered personal
information to the database as a new seller.
9. The system informs the Seller that the registration is successful.
10. The use case ends.

**Alternative Flows:**

**A1: Duplicate Username**

At {Username Validation}, if the entered username already exists across any user type
(Buyer, Seller, Admin),
1. The system displays an error message indicating the username is taken.
2. The flow of events is resumed at {Enter Personal Information}.

**A2: Invalid Password**

At {Password Validation} if the entered password does not meet validation rules (e.g.,
too short, missing numbers),
1. The system informs the user that the password is too weak to be used.
2. The flow of events is resumed at {Enter Personal Information}.

**A3: Invalid Confirm-password**

At {Confirm-password Validation} if the entered confirm-password does not have the
same value as the password,
1. The system removes the entered confirm-password.
2. The system informs the user that the confirm-password was incorrect.
3. The flow of events is resumed at {Enter Personal Information}.~~~~~~~~

## Use Case: Login Account

**Brief Description:** Allows a registered seller to securely access the Seller Portal.

**Use-case Diagram:**

**Preconditions:**

● The user has an existing, registered Seller account.
● The user is not currently logged into the system.

**Postconditions:**

● The Seller is successfully authenticated and logged into the system.
● The system state reflects an active Seller session.

**Basic Flow:**

1. The use case begins when the Seller chooses to log in.
2. The system displays the login screen.
**{Enter Login Details}**
3. The Seller enters their Username and Password.
**{Validate Credentials}**
4. The system validates the credentials against the registered user database.
**{Verify Account Type}**
5. The system verifies that the account type is "Seller".
6. The system grants access and redirects the Seller to the Seller Dashboard.
7. The use case ends.

**Alternative Flows:**

**A1: Invalid Credentials**

At {Validate Credentials} , if the username or password is incorrect,
1. The system displays an error message indicating invalid credentials.
2. The flow of events is resumed at {Enter Login Details}.

**A2: Incorrect Account Type**

At {Verify Account Type} , if the credentials belong to a Buyer or Admin account,
1. The system displays an error message indicating that this is not a seller account and
access is denied.
2. The flow of events is resumed at Step {Enter Login Details}.

## Use Case: Edit Personal Data

**Brief Description:** Allows a seller to update their personal details, password, bio, and
preferred meet-up locations. This updates their public profile and login credentials.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.

**Postconditions:**

● The Seller's personal details (Full Name, Bio, Meet-up locations) are updated in
the database.
● If a password change was requested, the new hashed password is updated in
the database.
● The public-facing profile information visible to Buyers is updated accordingly.

**Basic Flow:**

1. The use case begins when the Seller navigates to their Profile screen.
2. The system retrieves and displays the current profile information.
**{Edit Profile Details}**
3. The Seller edits the personal details (Full Name, Bio, Preferred meet-up
locations).
4. If the Seller wishes to change their password:
4.1. The Seller enters the new password and confirms it.
5. The Seller submits the updated profile.
**{Validate Profile Update}**
6. The system validates the updated credentials (e.g., Full Name not empty, valid
password criteria).
7. The system saves the updated data to the database, ensuring it updates both the
Seller's private account and their public Buyer-facing profile.
8. The system displays a confirmation message that the profile was successfully
updated.
9. The use case ends.

**Alternative Flows:**

**A1: Validation Failure**

At {Validate Profile Update} if the updated details fail validation (e.g., empty Full Name
or weak password),
1. The system displays an appropriate error message.
2. The flow of events is resumed at {Edit Profile Details}.

**A2: Cancel Action**

At {Edit Profile Details} if the Seller chooses to cancel the update,
1. The system discards any unsaved changes and returns to the Profile screen.
2. The use case ends.

## Use Case: Check Notification

**Brief Description:** Allows a Seller to view system notifications regarding their listings,
incoming offers, ratings, and platform announcements.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.

**Postconditions:**

● The selected notifications are marked as "read" in the database.

**Basic Flow:**

1. The use case begins when the Seller navigates to the Notification Board.
2. The system retrieves all notifications associated with the Seller's account (e.g.,
Listing approved/rejected, New offer received, Rating received, Deal completed).
3. The system displays the notifications, categorized and timestamped (newest
notification displayed at the top).
**{Select Notification}**
4. The Seller selects a specific notification to view its full details.
5. The system marks the selected notification as "read".
6. The use case ends.

**Alternative Flows:**

**A1: No Notifications Found**

At {Select Notification} if the system finds no notifications for the Seller,
1. The system displays a message indicating that there are no new notifications.
2. The use case ends.

## Use Case: Create Listing

**Brief Description:** Allows a seller to post a new item for sale, lend, or give away, which is
then submitted for admin approval.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.
● The Seller has all the necessary information required for the listing (Item Name,
Category, Price/Duration, etc.) and up to 6 photos ready.

**Postconditions:**

● A new listing is stored in the database with a status of "Pending Review".
● The Admin is notified of the new submission awaiting approval.
● The Seller is shown a confirmation message.

**Basic Flow:**

1. The use case begins when the Seller selects the "Create New Listing" option.
2. The system displays the new listing form.
3. The Seller selects the Listing Type (Sell, Lend, or Give Away).
**{Listing Type Selected}**
4. The system dynamically adjusts the required fields based on the selected type
4.1. If the “Sell” type is selected:
4.1.1. The system makes the Price field required and displays it.
4.1.2. The Seller enters the Price
4.2. If the “Lend” type is selected:
4.2.1. The system makes the Lending Duration field required and displays it.
4.2.2. The Seller enters the Lending Duration.
4.3. If the “Give Away” type is selected:
4.3.1. The system hides/disables the Price and Lending Duration fields.
**{Enter Item Details}**
5. The Seller enters the common item details for all listing types: Item Name, Category,
Condition, Description, and Meet-up Location.
**{Upload Photo}**
6. The Seller uploads up to 6 photos of the item.
7. The Seller submits the listing.
**{Validate Listing}**
8. The system validates the required fields and data formats based on the chosen type.
9. The system saves the listing with a status of "Pending Review" and sends a
notification to the Admin for approval.
10. The system displays a confirmation message to the Seller that the listing was
submitted successfully.
11. The use case ends.

**Alternative Flows:**

**A1: Missing Required Fields or Invalid Data**

At {Validate Listing} if any required fields are missing or contain invalid data (e.g.,
negative price, empty item name),
1. The system highlights the missing/invalid fields and displays an error message.
2. The flow of events is resumed at {Enter Item Details}.

**A2: Too Many Photos**

At {Upload Photo}  if the Seller attempts to upload more than 6 photos,
1. The system displays an error message indicating the maximum limit of 6 photos.
2. The flow of events is resumed at {Upload Photo}.

## Use Case: Manage Listing

**Brief Description:** Allows a seller to view, modify, or delete their existing listings.
Modifying a live listing triggers a re-approval request to the Admin.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.

**Postconditions:**

● If Listing is Modified: The listing details are updated in the database. The listing
status is reverted to "Pending Review", and the Admin is notified for re-approval.
● If Deleted: The listing is permanently removed from the database.
● The "My Listings" screen reflects the updated state of the Seller's inventory.

**Basic Flow:**

1. The use case begins when the Seller navigates to the "My Listings" screen.
2. The system retrieves and displays all listings posted by the Seller, including their
current status (Pending, Live, Rejected, Sold, Lent, Given).
**{Select Listing}**
3. The Seller selects a listing to modify or delete.
3.1. If the MODIFY action is selected:
**{Start Editing}**
3.1.1. The system displays the current listing details in an editable form.
3.1.2. The Seller updates the listing information.
3.1.3. The Seller submits the changes.
**{Validate Edit}**
3.1.4. The system validates the updated data.
3.1.5. The system saves the changes and updates the listing status back
to "Pending Review".
3.1.6. The system sends a notification to the Admin for re-approval.
3.1.7. The system displays a confirmation message to the Seller that the
changes are pending review.
3.2. If the DELETE action is selected:
3.2.1. The system displays a confirmation dialog.
**{Confirm Deletion}**
3.2.2. The Seller confirms the deletion.
3.2.3. The system removes the listing from the database.
3.2.4. The system displays a confirmation message to the Seller.
4. The use case ends.

**Alternative Flows:**

**A1: Validation Failure**

At {Validate Edit} if the updated details fail validation (e.g., negative price),
1. The system highlights the invalid fields and displays an error message.
2. The flow of events is resumed at {Start Editing}.

**A2: Cancel Action**

At {Start Editing} or {Confirm Deletion}, if the Seller cancels the action,
1. The system discards changes and returns to the "My Listings" screen.
2. The flow of events is resumed at {Select Listing}.

## Use Case: Manage Offer

**Brief Description:** Allows a Seller to view, accept, decline, counter, or chat about
incoming offers, borrow requests, and claims from buyers.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.

**Postconditions:**

● The offer is resolved (Accepted, Declined, or Countered).
● If accepted, the listing status is updated (Reserved/Sold/Lent/Given), and the
Buyer is notified with buyers’ My Deal screen changed accordingly.

**Basic Flow:**

1. The use case begins when the Seller navigates to the "Offer / Request
Management" screen.
**{Check Offers}**
2. The system retrieves and displays all pending offers, borrow requests, and
claims.
**{Select Offer}**
3. The Seller selects a specific offer to review.
4. The system displays the offer details (e.g., bid price, proposed dates, or claim
request).
**{Select Action}**
5. The Seller selects an action to perform:
5.1. If the ACCEPT action is selected:
5.1.1. The system updates the listing status to "Reserved", "Sold", "Lent",
or "Given" (depending on the listing type).
5.1.2. The system sends a notification to the Buyer.
5.1.3. The system updates the Buyer's "My Deals" screen to reflect the
accepted deal.
5.1.4. The system displays a confirmation message to the Seller.
5.2. If the DECLINE action is selected:
5.2.1. The system updates the offer status to "Declined".
5.2.2. The system sends a notification to the Buyer.
5.2.3. The system displays a confirmation message to the Seller.
5.3. If the OPEN CHAT action is selected (Only for Sell or Lend listings):
5.3.1 The system opens the private chat thread linked to the listing.
5.3.2. The Seller can send messages to negotiate or to counter with the
Buyer inside the private chat thread.
5.3.3 If the buyer offers a satisfactory price offer, the Seller can accept the
price offer and send a notification to the Buyer.
6. The use case ends.

**Alternative Flows:**

**A1: No Offers Received**

At {Check Offers} if the system finds no pending offers, borrow requests, or claims for
the Seller's listings,
1. The system displays an empty state message (e.g., "You currently have no
incoming offers or requests").
2. The use case ends.

**A2: Listing No Longer Available**

At {Select Action} if the Seller chooses to Accept, but the listing was already marked
as Sold/Lent/Given by another transaction,
1. The system displays an error message indicating the item is no longer available.
2. The flow of events is resumed at {Select Offer}.

## Use Case: Check Statistic

**Brief Description:** Allows a Seller to view graphical statistics regarding their listings,
views, deals, and ratings.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.

**Basic Flow:**

1. The use case begins when the Seller navigates to the "Seller Stats
Screen".
**{View Statistic}**
2. The system shows the relevant metrics (total number of listing views, total
offers/requests received, total completed deals, average rating from buyers)
and their graphical charts.
3. The use case ends.

**Alternative Flows:**

**A1: Insufficient Data**

At {View Statistic}, if the Seller has just created their account and has zero
views/offers/deals,
1. The system displays the metrics as "0" or "N/A".
2. The system displays a message indicating that charts will be generated
once the Seller receives more activity.
3. The use case ends.

## Use Case: Handle Review

**Brief Description:** Allows a seller to view, respond to, or flag reviews and feedback
left by buyers. Replies notify the Buyer; flags notify the Admin.

**Use-case Diagram:**

**Preconditions:**

● The Seller is logged into the system.

**Postconditions:**

● The Reviews section correctly reflects the updated state of the feedback.

**Basic Flow:**

1. The use case begins when the Seller navigates to the Reviews section.
2. The system retrieves and displays all reviews and ratings submitted by buyers.
**{Select Review}**
3. The Seller selects a specific review to act upon.
3.1. If the Seller chooses to RESPOND:
**{Enter Reply}**
3.1.1. The Seller enters a written reply.
3.1.2. The Seller submits the reply.
**{Validate Reply}**
3.1.3. The system validates the reply.
3.1.4. The system saves the reply and sends a notification to the Buyer.
3.1.5. The system displays a confirmation message.
3.2. If the Seller chooses to FLAG a review as inappropriate:
3.2.1. The system prompts the Seller to confirm the flag.
**{Confirm Flag}**
3.2.2. The Seller confirms.
3.2.3. The system sends the report to the Admin for moderation.
3.2.4. The system displays a confirmation message.
4. The use case ends.

**Alternative Flows:**

**A1: Empty Reply**

At {Validate Reply} if the Seller attempts to submit an empty reply,
1. The system displays an error message indicating a reply cannot be empty.
2. The flow of events is resumed at {Enter Reply}.

**A2: Cancel Action**

At {Enter Reply} or {Confirm Flag}, if the Seller cancels the action,
1. The system returns to the Reviews display screen.
2. The flow of events is resumed at {Select Review}.
