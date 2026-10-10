# Sportground

A booking system for a sports facility here in Manila. It has basketball, volleyball, badminton (2 courts each, Court A and B) and billiard (3 tables: A, B, C). Players can reserve a date and time online instead of just walking in.

Main part of the system: **Reservations**.

# What problem this solves

Right now ordinary courts use a logbook. Two groups can show up for the same court at the same time and argue about it. This system blocks double booking, checks the time is inside opening hours (8AM to 12 midnight), and keeps track of payments and refunds in a wallet so nothing gets lost.

# Features

**Landing page**
- Public page, no login needed. Shows the business name, the 4 sports, prices, and pictures.
- Has Book Now and Login buttons. Works on phone screens too.

**Users**
- Register with first name and last name in separate boxes, email, password. Then login and logout. Two accounts cannot share the same name or the same email.
- Email must end with @sportground.ph, for both register and login.
- Passwords are hashed (SHA-256 with salt), not saved as plain text.
- 5 wrong passwords locks you out: 30 seconds the first time, 1 minute the second time, then 30 seconds longer every next time. A countdown shows on the login page. Unknown email says no account is registered with it. If only the password is wrong, your typed email stays in the box.
- Two roles: admin and customers. Admins cannot book because booking is for customers only. Admins can see a masked password and reset a customer password from the Users list. Pages that need login will send you to the login page if you are not signed in.

**Reservations (CRUD)**
- Create: book a sport, court, date, start time (12-hour AM/PM list) and how many hours. Sports are shown capitalized (Basketball, Volleyball, Badminton, Billiard). The page shows what time you will end, like 8 AM + 1 hour ends 9 AM. It also shows a photo of the court you pick (files like `basketball-a.jpg`, `billiard.jpg` inside `public/images`).
- Read: dashboard, My Bookings page, availability table, and the admin list.
- Update: reschedule your own booking only, the admin cannot edit customer schedules (price difference is charged or refunded). Change your name and password on the Profile page, it asks for your current password first, then tells you what changed (name, password, or both).
- Delete: ask to cancel a booking, the admin must confirm it before it is cancelled and refunded. If the admin keeps it instead, a note appears in your wallet history. You can take back the request while it waits. Delete for real is for cancelled bookings only. Both ask for confirmation first.
- All data is saved in text files under `./data`, so it is still there after restart.

**Rules**
- Open 8:00 AM to 12:00 midnight. No booking before 8 AM or after 12 AM. Whole hours only (example 9:00 to 11:00), minimum 1 hour, no maximum.
- Same court + overlapping time = blocked. You can still book the other court or another time.
- Only 1 reservation per sport per user per day.
- Prices per hour: Basketball P300, Volleyball P200, Badminton P200, Billiard P150. Total = price x hours.
- Booking takes money from your wallet. Cancel = full refund to the wallet. Balance can never go below zero, so no debt. Only the admin can add money to a wallet. The admin account is not in the users list.

## Tech used

Just Java (JDK 17 and up), no frameworks or libraries. It uses the web server that already comes with Java. Data is stored in text files. The pages are plain HTML with one CSS file.

Files:
- `src/sportground/App.java` - routes and booking rules
- `src/sportground/Db.java` - loading and saving the text files
- `src/sportground/Auth.java` - password hashing and login sessions
- `src/sportground/Html.java` - landing page and login/register forms
- `public/` - CSS and images
- `Dockerfile` - for putting the site online (Render and others)

## How to run

You only need JDK 17 or newer. Check with `javac -version`.

```
run.bat
```

Or step by step:

```
mkdir out
javac -d out src\sportground\*.java
java -cp out sportground.App
```

Then open http://localhost:3000 in the browser. On Mac/Linux use `sh run.sh`.

## Test accounts

Made automatically on first run:

- Admin: `admin@sportground.ph` / `admin123` (can see all bookings, cancel with refund, add balance, change prices)
- Customer: `user@sportground.ph` / `user123` (starts with P2000 balance)

To try it: log in as the customer, book basketball Court A tomorrow 9 AM for 2 hours. Then try booking 10 AM on the same court, it will be blocked. Press Cancel on your booking, it becomes Pending. Then log in as admin and confirm the cancel, and the money goes back to the customer wallet.

## AI disclosure

I used OpenCode (Muse Spark) to help write the code, the CSS, and this README. I tested everything myself: booking, overlap blocking, refunds, edit, and delete.
