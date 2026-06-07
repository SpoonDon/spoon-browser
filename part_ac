            if (!input.startsWith("http://") &&
                    !input.startsWith("https://")) {

                url = "https://" + input;

            } else {

                url = input;
            }

        } else {

            url = "https://duckduckgo.com/?q=" +
                    input.replace(" ", "+");
        }

        getCurrentWebView().loadUrl(url);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        menu.add("Refresh");
        menu.add("Home");
        menu.add("About");

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        String title = item.getTitle().toString();

        switch (title) {

            case "Refresh":

                getCurrentWebView().reload();

                return true;

            case "Home":

                showHome();

                return true;

            case "About":

                Toast.makeText(
                        this,
                        "Spoon Browser",
                        Toast.LENGTH_SHORT
                ).show();

                return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
